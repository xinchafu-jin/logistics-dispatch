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
import com.example.backend.dto.request.NoSignatureRequestDTO;
import com.example.backend.dto.respones.DeliveryRecordResponse;
import com.example.backend.entity.DeliveryRecordsEntity;
import com.example.backend.entity.DriversEntity;
import com.example.backend.entity.ExceptionCasesEntity;
import com.example.backend.entity.OrdersEntity;
import com.example.backend.entity.RoutesEntity;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import static com.example.backend.constants.ValidMsg.*;

/** 司機抵達、完成交貨與無人簽收的共用交易流程。 */
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

    public DeliveryService(
            DeliveryRecordsDAO deliveryRecordsDAO,
            ExceptionCasesDAO exceptionCasesDAO,
            OrdersDAO ordersDAO,
            RoutesDAO routesDAO,
            DriversDAO driversDAO,
            RouteLegMileageService routeLegMileageService
    ) {
        this.deliveryRecordsDAO = deliveryRecordsDAO;
        this.exceptionCasesDAO = exceptionCasesDAO;
        this.ordersDAO = ordersDAO;
        this.routesDAO = routesDAO;
        this.driversDAO = driversDAO;
        this.routeLegMileageService = routeLegMileageService;
    }

    /** 抵達門市後建立本次配送紀錄，訂單進入配送中。 */
    public DeliveryRecordResponse arrive(Long driverId, ArriveRequestDTO request) {
        LocalDateTime now = LocalDateTime.now(TAIPEI);
        OrdersEntity order = findAuthorizedOrderForUpdate(driverId, request.getOrderId(), now.toLocalDate());
        requireOrderStatus(order, OrderStatus.CONFIRMED, DELIVERY_ARRIVE_STATUS_INVALID);

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

        OrdersEntity followUpOrder = createNoSignatureFollowUpOrder(order, now.toLocalDate().plusDays(1));

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

    private OrdersEntity createNoSignatureFollowUpOrder(
            OrdersEntity sourceOrder,
            LocalDate deliveryDate
    ) {
        int retryCount = valueOrZero(sourceOrder.getRetryCount()) + 1;
        OrdersEntity followUpOrder = new OrdersEntity();
        followUpOrder.setOrderNumber(noSignatureOrderNumber(
                sourceOrder.getId(), deliveryDate, retryCount));
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
        return ordersDAO.save(followUpOrder);
    }

    private String noSignatureOrderNumber(
            Long sourceOrderId,
            LocalDate deliveryDate,
            int retryCount
    ) {
        String compactDate = deliveryDate.format(DateTimeFormatter.BASIC_ISO_DATE).substring(2);
        String sourceId = Long.toString(sourceOrderId, 36).toUpperCase();
        String retry = Integer.toString(retryCount, 36).toUpperCase();
        return "NS-" + compactDate + "-" + sourceId + "-" + retry;
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
        return ordersDAO.save(followUpOrder);
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
