package com.example.backend.service;

import com.example.backend.constants.ExceptionStatus;
import com.example.backend.constants.ExceptionType;
import com.example.backend.constants.OrderStatus;
import com.example.backend.constants.OrderType;
import com.example.backend.constants.RouteStatus;
import com.example.backend.dao.DeliveryRecordsDAO;
import com.example.backend.dao.ExceptionCasesDAO;
import com.example.backend.dao.OrdersDAO;
import com.example.backend.dao.RoutesDAO;
import com.example.backend.dto.respones.ExceptionCaseResponse;
import com.example.backend.entity.DeliveryRecordsEntity;
import com.example.backend.entity.ExceptionCasesEntity;
import com.example.backend.entity.OrdersEntity;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 無人簽收隔日 06:00 自動送待排；其餘配送異常進主管確認區。 */
@Service
public class DeliveryExceptionService {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    private final ExceptionCasesDAO exceptionCasesDAO;
    private final DeliveryRecordsDAO deliveryRecordsDAO;
    private final OrdersDAO ordersDAO;
    private final RoutesDAO routesDAO;
    private final DispatchBoardPushService dispatchBoardPushService;

    public DeliveryExceptionService(
            ExceptionCasesDAO exceptionCasesDAO,
            DeliveryRecordsDAO deliveryRecordsDAO,
            OrdersDAO ordersDAO,
            RoutesDAO routesDAO,
            DispatchBoardPushService dispatchBoardPushService
    ) {
        this.exceptionCasesDAO = exceptionCasesDAO;
        this.deliveryRecordsDAO = deliveryRecordsDAO;
        this.ordersDAO = ordersDAO;
        this.routesDAO = routesDAO;
        this.dispatchBoardPushService = dispatchBoardPushService;
    }

    /** 每分鐘處理到期案件：無人簽收自動送待排，其餘送主管確認區。 */
    @Scheduled(cron = "0 * * * * *", zone = "Asia/Taipei")
    @Transactional
    public void queueDueCases() {
        queueDueCases(LocalDateTime.now(TAIPEI));
    }

    /** 查詢前再補掃一次，避免後端在 06:00 停機而漏掉自動處理。 */
    @Transactional
    public List<ExceptionCaseResponse> findPendingConfirmation() {
        queueDueCases(LocalDateTime.now(TAIPEI));
        List<ExceptionCasesEntity> pending = new ArrayList<>(exceptionCasesDAO
                .findByStatusAndQueuedAtIsNotNullOrderByQueuedAtAsc(ExceptionStatus.OPEN)
                .stream().filter(item -> item.getType() != ExceptionType.NO_SIGNATURE).toList());
        // 新的無人簽收案件在 06:00 前也要看得到，但不提供主管確認按鈕。
        pending.addAll(exceptionCasesDAO.findByTypeAndStatusOrderByIdAsc(
                        ExceptionType.NO_SIGNATURE, ExceptionStatus.OPEN).stream()
                .filter(item -> item.getStatus() == ExceptionStatus.OPEN
                        && item.getReviewAvailableAt() != null && item.getFollowUpOrderId() != null)
                .toList());
        pending.sort(Comparator.comparing(ExceptionCasesEntity::getReviewAvailableAt,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(ExceptionCasesEntity::getId));
        return pending.stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ExceptionCaseResponse> findAll(ExceptionStatus status, ExceptionType type) {
        return exceptionCasesDAO.findAll().stream()
                .filter(item -> status == null || item.getStatus() == status)
                .filter(item -> type == null || item.getType() == type)
                .sorted((left, right) -> right.getCreatedAt().compareTo(left.getCreatedAt()))
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public ExceptionCaseResponse closeGeneral(
            Long exceptionCaseId,
            String reviewedBy,
            String resolution
    ) {
        ExceptionCasesEntity exceptionCase = exceptionCasesDAO.findForUpdate(exceptionCaseId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "找不到配送異常，ID：" + exceptionCaseId));
        if (exceptionCase.getType() == ExceptionType.NO_SIGNATURE) {
            throw new IllegalArgumentException("無人簽收會在隔日 06:00 自動送入待排車；誤按請恢復原單配送");
        }
        if (exceptionCase.getType() == ExceptionType.DRIVER_REPORT) {
            // 要走 DriverCaseService.close：那邊才會推 CASE_CLOSED，司機端才知道結案了
            throw new IllegalArgumentException("司機回報請在異常中心的司機回報清單結案");
        }
        if (exceptionCase.getFollowUpOrderId() != null) {
            // 有補送單的要走 confirm：在這裡結案的話補送單會一直停在待確認，
            // 而 confirm 只收 OPEN 的案件，之後就沒有路把補送單送進待排車
            throw new IllegalArgumentException("這筆異常有補送單，請使用確認送入待排車");
        }
        if (exceptionCase.getStatus() != ExceptionStatus.OPEN) {
            throw new IllegalArgumentException("此配送異常已經結案");
        }
        exceptionCase.setStatus(ExceptionStatus.CLOSED);
        exceptionCase.setHandledBy(reviewedBy);
        exceptionCase.setHandledAt(LocalDateTime.now(TAIPEI));
        exceptionCase.setResolution(resolution.trim());
        exceptionCasesDAO.save(exceptionCase);
        return toResponse(exceptionCase);
    }

    /** 主管確認後結案，並把後續訂單送入可排車的 CONFIRMED 狀態。 */
    @Transactional
    public ExceptionCaseResponse confirm(Long exceptionCaseId, String reviewedBy) {
        LocalDateTime now = LocalDateTime.now(TAIPEI);
        ExceptionCasesEntity exceptionCase = exceptionCasesDAO.findForUpdate(exceptionCaseId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "找不到配送異常，ID：" + exceptionCaseId));
        if (exceptionCase.getStatus() != ExceptionStatus.OPEN) {
            throw new IllegalArgumentException("此配送異常已經結案");
        }
        if (exceptionCase.getType() == ExceptionType.NO_SIGNATURE) {
            throw new IllegalArgumentException("無人簽收於隔日 06:00 自動送入待排車，不需主管確認");
        }
        if (exceptionCase.getReviewAvailableAt() == null
                || exceptionCase.getReviewAvailableAt().isAfter(now)) {
            throw new IllegalArgumentException("此配送異常尚未到隔日確認時間");
        }
        if (exceptionCase.getQueuedAt() == null) {
            exceptionCase.setQueuedAt(now);
        }
        OrdersEntity followUpOrder;
        if (exceptionCase.getFollowUpOrderId() == null) {
            followUpOrder = createLegacyFollowUpOrder(exceptionCase, now.toLocalDate());
        } else {
            followUpOrder = ordersDAO.findForUpdate(exceptionCase.getFollowUpOrderId())
                    .orElseThrow(() -> new EntityNotFoundException(
                            "找不到後續訂單，ID：" + exceptionCase.getFollowUpOrderId()));
        }
        if (followUpOrder.getRouteId() != null) {
            throw new IllegalArgumentException("異常重建單已排車，請先確認現有指派，不能直接改日期");
        }
        if (followUpOrder.getStatus() == OrderStatus.PENDING_CONFIRM
                || followUpOrder.getStatus() == OrderStatus.CONFIRMED) {
            // 無人簽收已改為自動處理；這裡其餘異常都以主管確認的台北日期回到當天待排區。
            LocalDate previousDate = followUpOrder.getDeliveryDate();
            followUpOrder.setDeliveryDate(now.toLocalDate());
            followUpOrder.setStatus(OrderStatus.CONFIRMED);
            ordersDAO.save(followUpOrder);
            // EntityListener 只能看到新日期；主管若正看著舊日看板，也要通知它重讀並移除這張單。
            if (!previousDate.equals(followUpOrder.getDeliveryDate())) {
                dispatchBoardPushService.markChanged(previousDate);
            }
        } else if (followUpOrder.getStatus() != OrderStatus.CONFIRMED) {
            throw new IllegalArgumentException(
                    "後續訂單狀態不可確認：" + followUpOrder.getStatus());
        }

        exceptionCase.setStatus(ExceptionStatus.CLOSED);
        exceptionCase.setHandledBy(reviewedBy);
        exceptionCase.setHandledAt(now);
        exceptionCase.setResolution("主管已確認，後續訂單已送入待排車");
        exceptionCasesDAO.save(exceptionCase);
        return toResponse(exceptionCase);
    }

    /** 舊版異常沒有後續訂單；主管第一次確認時依新規則補建。 */
    private OrdersEntity createLegacyFollowUpOrder(
            ExceptionCasesEntity exceptionCase,
            LocalDate deliveryDate
    ) {
        if (exceptionCase.getOrderId() == null) {
            throw new IllegalArgumentException("此配送異常沒有來源訂單");
        }
        OrdersEntity sourceOrder = ordersDAO.findForUpdate(exceptionCase.getOrderId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "找不到來源訂單，ID：" + exceptionCase.getOrderId()));
        int retryCount = sourceOrder.getRetryCount() == null
                ? 1
                : sourceOrder.getRetryCount() + 1;

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
        followUpOrder = ordersDAO.save(followUpOrder);

        exceptionCase.setFollowUpOrderId(followUpOrder.getId());
        if (sourceOrder.getStatus() == OrderStatus.CONFIRMED
                || sourceOrder.getStatus() == OrderStatus.IN_DELIVERY) {
            sourceOrder.setStatus(OrderStatus.NO_SIGNATURE);
            ordersDAO.save(sourceOrder);
        }
        return followUpOrder;
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

    void queueDueCases(LocalDateTime now) {
        List<ExceptionCasesEntity> automaticCases = exceptionCasesDAO.findDueNoSignatureForUpdate(
                ExceptionStatus.OPEN, ExceptionType.NO_SIGNATURE, now);
        for (ExceptionCasesEntity exceptionCase : automaticCases) {
            autoDispatchNoSignature(exceptionCase, now);
        }
        List<ExceptionCasesEntity> dueCases = exceptionCasesDAO.findDueForUpdate(
                ExceptionStatus.OPEN, now, ExceptionType.NO_SIGNATURE);
        dueCases.forEach(item -> item.setQueuedAt(now));
        exceptionCasesDAO.saveAll(dueCases);
    }

    /** 已入人工佇列的舊案件也走這裡；鎖案件後只處理仍待確認的重送單，避免重複建單。 */
    private void autoDispatchNoSignature(ExceptionCasesEntity exceptionCase, LocalDateTime now) {
        if (exceptionCase.getStatus() != ExceptionStatus.OPEN
                || exceptionCase.getType() != ExceptionType.NO_SIGNATURE
                || exceptionCase.getReviewAvailableAt() == null
                || exceptionCase.getReviewAvailableAt().isAfter(now)) {
            return;
        }
        OrdersEntity followUpOrder;
        if (exceptionCase.getFollowUpOrderId() == null) {
            // 舊示範案件有缺來源訂單的孤兒紀錄；保留原樣，不讓它擋住其他可自動排車的案件。
            if (exceptionCase.getOrderId() == null
                    || ordersDAO.findForUpdate(exceptionCase.getOrderId()).isEmpty()) {
                return;
            }
            followUpOrder = createLegacyFollowUpOrder(exceptionCase, now.toLocalDate());
        } else {
            var existing = ordersDAO.findForUpdate(exceptionCase.getFollowUpOrderId());
            if (existing.isEmpty()) {
                return;
            }
            followUpOrder = existing.get();
        }
        if (followUpOrder.getStatus() != OrderStatus.PENDING_CONFIRM
                || followUpOrder.getRouteId() != null) {
            // 已另行處理的舊資料不能在排程中擅自改日期或撤銷既有指派。
            return;
        }

        LocalDate previousDate = followUpOrder.getDeliveryDate();
        followUpOrder.setDeliveryDate(nextDispatchDate(followUpOrder, now.toLocalDate()));
        followUpOrder.setStatus(OrderStatus.CONFIRMED);
        ordersDAO.save(followUpOrder);
        if (!previousDate.equals(followUpOrder.getDeliveryDate())) {
            dispatchBoardPushService.markChanged(previousDate);
        }

        if (exceptionCase.getQueuedAt() == null) {
            exceptionCase.setQueuedAt(now);
        }
        exceptionCase.setStatus(ExceptionStatus.CLOSED);
        exceptionCase.setHandledBy("系統自動送待排");
        exceptionCase.setHandledAt(now);
        exceptionCase.setResolution("無人簽收已自動送入 " + followUpOrder.getDeliveryDate() + " 的待排車區");
        exceptionCasesDAO.save(exceptionCase);
    }

    private LocalDate nextDispatchDate(OrdersEntity order, LocalDate today) {
        LocalDate candidate = order.getDeliveryDate().isBefore(today)
                ? today
                : order.getDeliveryDate();
        while (routesDAO.findByDateAndWarehouseId(candidate, order.getWarehouseId())
                .stream()
                .anyMatch(route -> route.getStatus() == RouteStatus.PUBLISHED)) {
            candidate = candidate.plusDays(1);
        }
        return candidate;
    }

    public ExceptionCaseResponse toResponse(ExceptionCasesEntity exceptionCase) {
        OrdersEntity sourceOrder = exceptionCase.getOrderId() == null
                ? null
                : ordersDAO.findById(exceptionCase.getOrderId()).orElse(null);
        DeliveryRecordsEntity deliveryRecord = exceptionCase.getDeliveryRecordId() == null
                ? null
                : deliveryRecordsDAO.findById(exceptionCase.getDeliveryRecordId()).orElse(null);
        OrdersEntity followUpOrder = exceptionCase.getFollowUpOrderId() == null
                ? null
                : ordersDAO.findById(exceptionCase.getFollowUpOrderId()).orElse(null);

        return new ExceptionCaseResponse(
                exceptionCase.getId(),
                exceptionCase.getType(),
                exceptionCase.getStatus(),
                exceptionCase.getDescription(),
                sourceOrder == null ? null : sourceOrder.getId(),
                sourceOrder == null ? null : sourceOrder.getOrderNumber(),
                sourceOrder == null ? null : sourceOrder.getOrderType(),
                deliveryRecord == null ? null : deliveryRecord.getId(),
                deliveryRecord == null ? null : deliveryRecord.getExpectedBoxCount(),
                deliveryRecord == null ? null : deliveryRecord.getDeliveredBoxCount(),
                deliveryRecord == null ? null : deliveryRecord.getShortageBoxCount(),
                deliveryRecord == null ? null : deliveryRecord.getDamagedBoxCount(),
                deliveryRecord == null ? null : deliveryRecord.getReplacementRequiredBoxCount(),
                followUpOrder == null ? null : followUpOrder.getId(),
                followUpOrder == null ? null : followUpOrder.getOrderNumber(),
                followUpOrder == null ? null : followUpOrder.getOrderType(),
                followUpOrder == null ? null : followUpOrder.getStatus(),
                followUpOrder == null ? null : followUpOrder.getDeliveryDate(),
                exceptionCase.getReviewAvailableAt(),
                exceptionCase.getQueuedAt(),
                exceptionCase.getCreatedAt(),
                exceptionCase.getHandledBy(),
                exceptionCase.getHandledAt(),
                exceptionCase.getResolution()
        );
    }
}
