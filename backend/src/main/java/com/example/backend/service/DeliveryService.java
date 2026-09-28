package com.example.backend.service;

import com.example.backend.constants.ExceptionStatus;
import com.example.backend.constants.ExceptionType;
import com.example.backend.constants.OrderStatus;
import com.example.backend.constants.OrderType;
import com.example.backend.constants.RouteStatus;
import com.example.backend.dao.DeliveryRecordsDAO;
import com.example.backend.dao.DriversDAO;
import com.example.backend.dao.ExceptionCasesDAO;
import com.example.backend.dao.OrdersDAO;
import com.example.backend.dao.RoutesDAO;
import com.example.backend.dto.request.ArriveRequestDTO;
import com.example.backend.dto.request.DeliverRequestDTO;
import com.example.backend.dto.request.LoadingItemDTO;
import com.example.backend.dto.request.LoadingRequestDTO;
import com.example.backend.dto.request.NoSignatureRequestDTO;
import com.example.backend.dto.respones.DeliveryRecordResponse;
import com.example.backend.dto.respones.LoadingResponse;
import com.example.backend.entity.DeliveryRecordsEntity;
import com.example.backend.entity.DriversEntity;
import com.example.backend.entity.ExceptionCasesEntity;
import com.example.backend.entity.OrderItemsEntity;
import com.example.backend.entity.OrdersEntity;
import com.example.backend.entity.RoutesEntity;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.example.backend.constants.ValidMsg.*;

/** 司機倉庫點交、抵達、完成交貨與無人簽收的共用交易流程。 */
@Service
@Transactional
public class DeliveryService {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private final DeliveryRecordsDAO deliveryRecordsDAO;
    private final ExceptionCasesDAO exceptionCasesDAO;
    private final OrdersDAO ordersDAO;
    private final RoutesDAO routesDAO;
    private final DriversDAO driversDAO;
    private final RouteLegMileageService routeLegMileageService;
    private final PreTripInspectionService preTripInspectionService;

    public DeliveryService(
            DeliveryRecordsDAO deliveryRecordsDAO,
            ExceptionCasesDAO exceptionCasesDAO,
            OrdersDAO ordersDAO,
            RoutesDAO routesDAO,
            DriversDAO driversDAO,
            RouteLegMileageService routeLegMileageService,
            PreTripInspectionService preTripInspectionService
    ) {
        this.deliveryRecordsDAO = deliveryRecordsDAO;
        this.exceptionCasesDAO = exceptionCasesDAO;
        this.ordersDAO = ordersDAO;
        this.routesDAO = routesDAO;
        this.driversDAO = driversDAO;
        this.routeLegMileageService = routeLegMileageService;
        this.preTripInspectionService = preTripInspectionService;
    }

    /** 倉庫點交：箱數相符轉為 LOADED；不符時原單 FAILED，並建立異常單與明日補送單。 */
    public LoadingResponse load(Long driverId, LoadingRequestDTO request) {
        LocalDateTime now = LocalDateTime.now(TAIPEI);
        // 出車前安全檢查通過才能點交。requirePassed 會鎖路線，所以要在鎖訂單之前呼叫：
        // 撤回也是先鎖路線，兩邊都「先路線、後訂單」，才不會一邊等一邊、互相卡死
        Long routeId = routeIdOf(request.getOrderId());
        preTripInspectionService.requirePassed(driverId, routeId);
        OrdersEntity order = findAuthorizedOrderForUpdate(driverId, request.getOrderId(), now.toLocalDate());
        // 上面查路線時還沒鎖訂單，這段時間訂單可能被移到別條路線，剛才通過的檢查就不算數
        if (!routeId.equals(order.getRouteId())) {
            throw new IllegalArgumentException("任務已變更，請重新整理今日任務");
        }
        // 連按兩次時，第二次進來單子已經是 LOADED 或 FAILED，會在這裡被擋下
        requireOrderStatus(order, OrderStatus.CONFIRMED, DELIVERY_LOADING_STATUS_INVALID);

        int expected = order.getBoxCount();
        int loaded = request.getLoadedBoxCount();
        // 點到的比單子多，是別張單的箱子混進來，退回倉庫即可，不算這張單的異常
        if (loaded > expected) {
            throw new IllegalArgumentException(DELIVERY_LOADING_OVER_COUNT.formatted(expected));
        }

        List<LoadingResponse.ItemResult> itemResults = validateAndRecordItems(
                order, driverId, request.getItems(), now);
        boolean itemContentsMatched = itemResults.stream().allMatch(LoadingResponse.ItemResult::getMatched);

        if (loaded == expected && itemContentsMatched) {
            order.setStatus(OrderStatus.LOADED);
            order.setLoadedAt(now);
            ordersDAO.save(order);
            return loadingResponse(order, null, null, itemResults);
        }

        // 箱數不符比照無人簽收：原單今天結案，整張由明日補送單重送；主管在異常中心確認後，補送單才進待排車
        OrdersEntity followUpOrder = createRedeliveryOrder(order, "LD", now.toLocalDate().plusDays(1));

        ExceptionCasesEntity exceptionCase = new ExceptionCasesEntity();
        exceptionCase.setOrderId(order.getId());
        exceptionCase.setFollowUpOrderId(followUpOrder.getId());
        // 異常中心只列已送進確認區的案件；設成現在，下一次排程或查詢就會送進去，主管當下就看得到
        exceptionCase.setReviewAvailableAt(now);
        exceptionCase.setType(ExceptionType.LOADING_MISMATCH);
        exceptionCase.setDescription(loadingMismatchDescription(
                expected, loaded, request.getNotes(), itemResults));
        exceptionCase.setStatus(ExceptionStatus.OPEN);
        exceptionCase = exceptionCasesDAO.save(exceptionCase);

        order.setStatus(OrderStatus.FAILED);
        ordersDAO.save(order);
        return loadingResponse(order, exceptionCase, followUpOrder, itemResults);
    }

    /** 抵達門市後建立本次配送紀錄，訂單進入配送中。 */
    public DeliveryRecordResponse arrive(Long driverId, ArriveRequestDTO request) {
        LocalDateTime now = LocalDateTime.now(TAIPEI);
        OrdersEntity order = findAuthorizedOrderForUpdate(driverId, request.getOrderId(), now.toLocalDate());
        requireOrderStatus(order, OrderStatus.LOADED, DELIVERY_ARRIVE_STATUS_INVALID);

        if (deliveryRecordsDAO.findFirstByOrderIdOrderByIdDesc(order.getId())
                .filter(this::isInProgress)
                .isPresent()) {
            throw new IllegalArgumentException(DELIVERY_ALREADY_ARRIVED);
        }

        DeliveryRecordsEntity record = new DeliveryRecordsEntity();
        record.setOrderId(order.getId());
        record.setArrivedAt(now);
        record.setExpectedBoxCount(order.getBoxCount());
        record.setShortageBoxCount(0);
        record.setDamagedBoxCount(0);
        record.setReplacementRequiredBoxCount(0);
        record.setNoSignature(false);

        order.setStatus(OrderStatus.IN_DELIVERY);
        ordersDAO.save(order);
        record = deliveryRecordsDAO.save(record);
        routeLegMileageService.recordArrival(driverId, order, record, now);
        return toResponse(record, order, null, null);
    }

    /** 完成交貨，保存實際箱數與備註並將訂單設為完成。 */
    public DeliveryRecordResponse deliver(Long driverId, DeliverRequestDTO request) {
        LocalDateTime now = LocalDateTime.now(TAIPEI);
        OrdersEntity order = findAuthorizedOrderForUpdate(driverId, request.getOrderId(), now.toLocalDate());
        requireOrderStatus(order, OrderStatus.IN_DELIVERY, DELIVERY_DELIVER_STATUS_INVALID);

        int expected = order.getBoxCount();
        int delivered = request.getBoxCount();
        int shortage = request.getShortageBoxCount() == null
                ? expected - delivered : request.getShortageBoxCount();
        int damaged = valueOrZero(request.getDamagedBoxCount());
        int replacement = valueOrZero(request.getReplacementRequiredBoxCount());
        validateDeliveryCounts(expected, delivered, shortage, damaged, replacement);

        DeliveryRecordsEntity record = findInProgressRecord(order.getId());
        record.setDeliveredAt(now);
        record.setHandledAt(now);
        record.setExpectedBoxCount(order.getBoxCount());
        record.setDeliveredBoxCount(delivered);
        record.setShortageBoxCount(shortage);
        record.setDamagedBoxCount(damaged);
        record.setReplacementRequiredBoxCount(replacement);
        record.setPhotoUrl(trimToNull(request.getPhotoUrl()));
        record.setNotes(trimToNull(request.getNotes()));
        record.setNoSignature(false);

        record = deliveryRecordsDAO.save(record);
        order.setStatus(OrderStatus.COMPLETED);
        ordersDAO.save(order);
        OrdersEntity followUpOrder = replacement > 0
                ? createReplacementOrder(order, replacement, now.toLocalDate().plusDays(1))
                : null;
        Long exceptionCaseId = createQualityExceptionIfNeeded(
                order, record, shortage, damaged, replacement, followUpOrder);
        return toResponse(record, order, exceptionCaseId, followUpOrder);
    }

    /** 無人簽收會保留原單與本次紀錄，並建立隔日待確認的重送新單。 */
    public DeliveryRecordResponse noSignature(Long driverId, NoSignatureRequestDTO request) {
        LocalDateTime now = LocalDateTime.now(TAIPEI);
        OrdersEntity order = findAuthorizedOrderForUpdate(driverId, request.getOrderId(), now.toLocalDate());
        requireOrderStatus(order, OrderStatus.IN_DELIVERY, DELIVERY_NO_SIGNATURE_STATUS_INVALID);

        DeliveryRecordsEntity record = findInProgressRecord(order.getId());
        record.setExpectedBoxCount(order.getBoxCount());
        record.setDeliveredBoxCount(0);
        record.setShortageBoxCount(0);
        record.setDamagedBoxCount(0);
        record.setReplacementRequiredBoxCount(0);
        record.setPhotoUrl(trimToNull(request.getPhotoUrl()));
        record.setNotes(trimToNull(request.getNotes()));
        record.setNoSignature(true);
        record.setHandledAt(now);
        record = deliveryRecordsDAO.save(record);

        OrdersEntity followUpOrder = createRedeliveryOrder(order, "NS", now.toLocalDate().plusDays(1));

        ExceptionCasesEntity exceptionCase = new ExceptionCasesEntity();
        exceptionCase.setOrderId(order.getId());
        exceptionCase.setDeliveryRecordId(record.getId());
        exceptionCase.setFollowUpOrderId(followUpOrder.getId());
        exceptionCase.setReviewAvailableAt(followUpOrder.getDeliveryDate().atTime(6, 0));
        exceptionCase.setType(ExceptionType.NO_SIGNATURE);
        exceptionCase.setDescription(
                record.getNotes() == null ? DELIVERY_NO_SIGNATURE_DESCRIPTION : record.getNotes());
        exceptionCase.setStatus(ExceptionStatus.OPEN);
        exceptionCase = exceptionCasesDAO.save(exceptionCase);

        order.setStatus(OrderStatus.NO_SIGNATURE);
        ordersDAO.save(order);
        return toResponse(record, order, exceptionCase.getId(), followUpOrder);
    }

    /** 整張原單改日重送。無人簽收（NS）與點交不符（LD）共用，單號前綴區分是哪一種。 */
    private OrdersEntity createRedeliveryOrder(
            OrdersEntity sourceOrder,
            String numberPrefix,
            LocalDate deliveryDate
    ) {
        int retryCount = valueOrZero(sourceOrder.getRetryCount()) + 1;
        OrdersEntity followUpOrder = new OrdersEntity();
        followUpOrder.setOrderNumber(redeliveryOrderNumber(
                numberPrefix, sourceOrder.getId(), deliveryDate, retryCount));
        followUpOrder.setStoreId(sourceOrder.getStoreId());
        followUpOrder.setWarehouseId(sourceOrder.getWarehouseId());
        followUpOrder.setSourceVendor(sourceOrder.getSourceVendor());
        followUpOrder.setItemDescription(sourceOrder.getItemDescription());
        followUpOrder.setBoxCount(sourceOrder.getBoxCount());
        followUpOrder.setNotes(sourceOrder.getNotes());
        followUpOrder.setDeliveryDate(deliveryDate);
        followUpOrder.setStatus(OrderStatus.PENDING_CONFIRM);
        followUpOrder.setOrderType(OrderType.REDELIVERY);
        followUpOrder.setParentOrderId(sourceOrder.getId());
        followUpOrder.setRetryCount(retryCount);
        copyItems(sourceOrder, followUpOrder);
        return ordersDAO.save(followUpOrder);
    }

    private String redeliveryOrderNumber(
            String prefix,
            Long sourceOrderId,
            LocalDate deliveryDate,
            int retryCount
    ) {
        String compactDate = deliveryDate.format(DateTimeFormatter.BASIC_ISO_DATE).substring(2);
        String sourceId = Long.toString(sourceOrderId, 36).toUpperCase();
        String retry = Integer.toString(retryCount, 36).toUpperCase();
        return prefix + "-" + compactDate + "-" + sourceId + "-" + retry;
    }

    private String loadingMismatchDescription(
            int expected,
            int loaded,
            String notes,
            List<LoadingResponse.ItemResult> itemResults
    ) {
        String description = DELIVERY_LOADING_MISMATCH_DESCRIPTION.formatted(expected, loaded);
        String itemMismatch = itemResults.stream()
                .filter(item -> !item.getMatched())
                .map(item -> "%s 應到 %d%s、實點 %d%s".formatted(
                        item.getItemName(), item.getExpectedQuantity(), item.getUnit(),
                        item.getLoadedQuantity(), item.getUnit()))
                .reduce((left, right) -> left + "；" + right)
                .orElse(null);
        if (itemMismatch != null) {
            description += "；內容物不符：" + itemMismatch;
        }
        String driverNotes = trimToNull(notes);
        return driverNotes == null ? description : description + "；司機備註：" + driverNotes;
    }

    private Long createQualityExceptionIfNeeded(
            OrdersEntity order,
            DeliveryRecordsEntity record,
            int shortage,
            int damaged,
            int replacement,
            OrdersEntity followUpOrder
    ) {
        if (shortage == 0 && damaged == 0) {
            return null;
        }
        ExceptionCasesEntity exceptionCase = new ExceptionCasesEntity();
        exceptionCase.setOrderId(order.getId());
        exceptionCase.setDeliveryRecordId(record.getId());
        exceptionCase.setType(shortage > 0 && damaged > 0
                ? ExceptionType.SHORTAGE_AND_DAMAGE
                : shortage > 0 ? ExceptionType.SHORTAGE : ExceptionType.DAMAGE);
        exceptionCase.setDescription("交貨短少 " + shortage + " 箱、損壞 " + damaged
                + " 箱、需補送 " + replacement + " 箱");
        if (followUpOrder != null) {
            exceptionCase.setFollowUpOrderId(followUpOrder.getId());
            exceptionCase.setReviewAvailableAt(followUpOrder.getDeliveryDate().atTime(6, 0));
        }
        exceptionCase.setStatus(ExceptionStatus.OPEN);
        return exceptionCasesDAO.save(exceptionCase).getId();
    }

    private OrdersEntity createReplacementOrder(
            OrdersEntity sourceOrder,
            int replacementBoxes,
            LocalDate deliveryDate
    ) {
        int retryCount = valueOrZero(sourceOrder.getRetryCount()) + 1;
        OrdersEntity followUpOrder = new OrdersEntity();
        followUpOrder.setOrderNumber("RP-"
                + deliveryDate.format(DateTimeFormatter.BASIC_ISO_DATE).substring(2)
                + "-" + Long.toString(sourceOrder.getId(), 36).toUpperCase()
                + "-" + Integer.toString(retryCount, 36).toUpperCase());
        followUpOrder.setStoreId(sourceOrder.getStoreId());
        followUpOrder.setWarehouseId(sourceOrder.getWarehouseId());
        followUpOrder.setSourceVendor(sourceOrder.getSourceVendor());
        followUpOrder.setItemDescription(sourceOrder.getItemDescription());
        followUpOrder.setBoxCount(replacementBoxes);
        followUpOrder.setNotes("來源訂單 " + sourceOrder.getOrderNumber() + " 的品質異常補送");
        followUpOrder.setDeliveryDate(deliveryDate);
        followUpOrder.setStatus(OrderStatus.PENDING_CONFIRM);
        followUpOrder.setOrderType(OrderType.REPLENISHMENT);
        followUpOrder.setParentOrderId(sourceOrder.getId());
        followUpOrder.setRetryCount(retryCount);
        copyItems(sourceOrder, followUpOrder);
        return ordersDAO.save(followUpOrder);
    }

    private List<LoadingResponse.ItemResult> validateAndRecordItems(
            OrdersEntity order,
            Long driverId,
            List<LoadingItemDTO> requestedItems,
            LocalDateTime checkedAt
    ) {
        List<OrderItemsEntity> expectedItems = order.getItems();
        if (expectedItems.isEmpty()) {
            if (requestedItems != null && !requestedItems.isEmpty()) {
                throw new IllegalArgumentException("這張訂單沒有可點交的內容物明細");
            }
            return List.of();
        }
        if (requestedItems == null || requestedItems.isEmpty()) {
            throw new IllegalArgumentException("請先逐項勾選並核對訂單內容物");
        }

        Map<Long, LoadingItemDTO> requestedById = new HashMap<>();
        for (LoadingItemDTO requested : requestedItems) {
            if (requested.getOrderItemId() == null) {
                throw new IllegalArgumentException("點交商品 ID 不能為空");
            }
            if (requestedById.put(requested.getOrderItemId(), requested) != null) {
                throw new IllegalArgumentException("同一個商品不能重複點交，ID：" + requested.getOrderItemId());
            }
        }

        Set<Long> expectedIds = new HashSet<>();
        for (OrderItemsEntity item : expectedItems) {
            expectedIds.add(item.getId());
            LoadingItemDTO requested = requestedById.get(item.getId());
            if (requested == null || !Boolean.TRUE.equals(requested.getChecked())) {
                throw new IllegalArgumentException("請先勾選並核對商品：" + item.getItemName());
            }
            int actual = requested.getLoadedQuantity() == null
                    ? item.getExpectedQuantity() : requested.getLoadedQuantity();
            if (actual > item.getExpectedQuantity()) {
                throw new IllegalArgumentException("商品「" + item.getItemName() + "」實點數量不能大於應到數量 "
                        + item.getExpectedQuantity() + item.getUnit());
            }
        }
        if (!expectedIds.equals(requestedById.keySet())) {
            throw new IllegalArgumentException("點交內容包含不屬於這張訂單的商品");
        }

        return expectedItems.stream().map(item -> {
            LoadingItemDTO requested = requestedById.get(item.getId());
            int actual = requested.getLoadedQuantity() == null
                    ? item.getExpectedQuantity() : requested.getLoadedQuantity();
            item.setLoadedQuantity(actual);
            item.setCheckedAt(checkedAt);
            item.setCheckedByDriverId(driverId);
            item.setLoadingNotes(trimToNull(requested.getNotes()));
            return new LoadingResponse.ItemResult(
                    item.getId(),
                    item.getItemName(),
                    item.getExpectedQuantity(),
                    actual,
                    item.getUnit(),
                    actual == item.getExpectedQuantity(),
                    checkedAt,
                    item.getLoadingNotes()
            );
        }).toList();
    }

    private LoadingResponse loadingResponse(
            OrdersEntity order,
            ExceptionCasesEntity exceptionCase,
            OrdersEntity followUpOrder,
            List<LoadingResponse.ItemResult> items
    ) {
        int checkedItemCount = (int) items.stream().filter(LoadingResponse.ItemResult::getMatched).count();
        return new LoadingResponse(
                order.getId(),
                order.getStatus(),
                order.getLoadedAt(),
                exceptionCase == null ? null : exceptionCase.getId(),
                followUpOrder == null ? null : followUpOrder.getId(),
                followUpOrder == null ? null : followUpOrder.getOrderNumber(),
                followUpOrder == null ? null : followUpOrder.getDeliveryDate(),
                checkedItemCount,
                items.size(),
                checkedItemCount == items.size(),
                items
        );
    }

    private void copyItems(OrdersEntity sourceOrder, OrdersEntity targetOrder) {
        for (OrderItemsEntity source : sourceOrder.getItems()) {
            OrderItemsEntity copy = new OrderItemsEntity();
            copy.setProductCode(source.getProductCode());
            copy.setItemName(source.getItemName());
            copy.setExpectedQuantity(source.getExpectedQuantity());
            copy.setUnit(source.getUnit());
            copy.setSequence(source.getSequence());
            copy.setNotes(source.getNotes());
            targetOrder.addItem(copy);
        }
    }

    /** 這張單排在哪條路線；只讀、不鎖，鎖訂單要等路線鎖好之後（見 load） */
    private Long routeIdOf(Long orderId) {
        OrdersEntity order = ordersDAO.findById(orderId)
                .orElseThrow(() -> new EntityNotFoundException(DELIVERY_ORDER_NOT_FOUND.formatted(orderId)));
        if (order.getRouteId() == null) {
            throw new IllegalArgumentException(DELIVERY_ROUTE_REQUIRED);
        }
        return order.getRouteId();
    }

    private OrdersEntity findAuthorizedOrderForUpdate(Long driverId, Long orderId, LocalDate today) {
        requireActiveDriver(driverId);
        OrdersEntity order = ordersDAO.findForUpdate(orderId)
                .orElseThrow(() -> new EntityNotFoundException(
                        DELIVERY_ORDER_NOT_FOUND.formatted(orderId)));
        if (order.getRouteId() == null) {
            throw new IllegalArgumentException(DELIVERY_ROUTE_REQUIRED);
        }

        RoutesEntity route = routesDAO.findById(order.getRouteId())
                .orElseThrow(() -> new EntityNotFoundException(
                        DELIVERY_ROUTE_NOT_FOUND.formatted(order.getRouteId())));
        if (route.getStatus() != RouteStatus.PUBLISHED) {
            throw new IllegalArgumentException(DELIVERY_ROUTE_NOT_PUBLISHED);
        }
        if (!today.equals(route.getDate())) {
            throw new IllegalArgumentException(DELIVERY_TODAY_ONLY);
        }
        if (!driverId.equals(route.getDriverId())) {
            throw new IllegalArgumentException(DELIVERY_WRONG_DRIVER);
        }
        if (order.getAssignedDriverId() != null
                && !driverId.equals(order.getAssignedDriverId())) {
            throw new IllegalArgumentException(DELIVERY_DRIVER_MISMATCH);
        }
        return order;
    }

    private void requireActiveDriver(Long driverId) {
        DriversEntity driver = driversDAO.findById(driverId)
                .orElseThrow(() -> new EntityNotFoundException(
                        DELIVERY_DRIVER_NOT_FOUND.formatted(driverId)));
        if (!Boolean.TRUE.equals(driver.getIsActive())) {
            throw new IllegalArgumentException(DELIVERY_DRIVER_INACTIVE);
        }
    }

    private DeliveryRecordsEntity findInProgressRecord(Long orderId) {
        DeliveryRecordsEntity record = deliveryRecordsDAO.findFirstByOrderIdOrderByIdDesc(orderId)
                .orElseThrow(() -> new IllegalArgumentException(DELIVERY_NOT_ARRIVED));
        if (!isInProgress(record)) {
            throw new IllegalArgumentException(DELIVERY_ALREADY_FINISHED);
        }
        return record;
    }

    private boolean isInProgress(DeliveryRecordsEntity record) {
        return record.getArrivedAt() != null
                && record.getDeliveredAt() == null
                && !Boolean.TRUE.equals(record.getNoSignature());
    }

    private void requireOrderStatus(OrdersEntity order, OrderStatus expected, String message) {
        if (order.getStatus() != expected) {
            throw new IllegalArgumentException(
                    message + DELIVERY_STATUS_SUFFIX.formatted(order.getStatus()));
        }
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private int valueOrZero(Integer value) {
        return value == null ? 0 : value;
    }

    private void validateDeliveryCounts(
            int expected,
            int delivered,
            int shortage,
            int damaged,
            int replacement
    ) {
        if (delivered < 0 || shortage < 0 || damaged < 0 || replacement < 0) {
            throw new IllegalArgumentException("交貨箱數不能小於 0");
        }
        if (delivered + shortage != expected) {
            throw new IllegalArgumentException(
                    "實際交付箱數加缺少箱數必須等於應到箱數 " + expected);
        }
        if (damaged > delivered) {
            throw new IllegalArgumentException("損壞箱數不能大於實際交付箱數");
        }
        if (replacement > shortage + damaged) {
            throw new IllegalArgumentException("需要補送箱數不能大於缺少箱數與損壞箱數總和");
        }
    }

    private DeliveryRecordResponse toResponse(
            DeliveryRecordsEntity record,
            OrdersEntity order,
            Long exceptionCaseId,
            OrdersEntity followUpOrder
    ) {
        return new DeliveryRecordResponse(
                record.getId(),
                record.getOrderId(),
                order.getStatus(),
                record.getArrivedAt(),
                record.getDeliveredAt(),
                record.getExpectedBoxCount(),
                record.getDeliveredBoxCount(),
                record.getShortageBoxCount(),
                record.getDamagedBoxCount(),
                record.getReplacementRequiredBoxCount(),
                record.getPhotoUrl(),
                record.getNotes(),
                record.getNoSignature(),
                exceptionCaseId,
                followUpOrder == null ? null : followUpOrder.getId(),
                followUpOrder == null ? null : followUpOrder.getOrderNumber(),
                followUpOrder == null ? null : followUpOrder.getDeliveryDate()
        );
    }
}
