package com.example.backend.service;

import com.example.backend.constants.ExceptionStatus;
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
import java.util.List;

/** 隔日配送異常進入主管確認區及結案的流程。 */
@Service
public class DeliveryExceptionService {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    private final ExceptionCasesDAO exceptionCasesDAO;
    private final DeliveryRecordsDAO deliveryRecordsDAO;
    private final OrdersDAO ordersDAO;
    private final RoutesDAO routesDAO;

    public DeliveryExceptionService(
            ExceptionCasesDAO exceptionCasesDAO,
            DeliveryRecordsDAO deliveryRecordsDAO,
            OrdersDAO ordersDAO,
            RoutesDAO routesDAO
    ) {
        this.exceptionCasesDAO = exceptionCasesDAO;
        this.deliveryRecordsDAO = deliveryRecordsDAO;
        this.ordersDAO = ordersDAO;
        this.routesDAO = routesDAO;
    }

    /** 每分鐘將已到隔日 06:00 的案件送入主管確認區。 */
    @Scheduled(cron = "0 * * * * *", zone = "Asia/Taipei")
    @Transactional
    public void queueDueCases() {
        queueDueCases(LocalDateTime.now(TAIPEI));
    }

    /** 查詢前再補掃一次，避免後端在排程時間停機而漏掉案件。 */
    @Transactional
    public List<ExceptionCaseResponse> findPendingConfirmation() {
        queueDueCases(LocalDateTime.now(TAIPEI));
        return exceptionCasesDAO
                .findByStatusAndQueuedAtIsNotNullOrderByQueuedAtAsc(ExceptionStatus.OPEN)
                .stream()
                .map(this::toResponse)
                .toList();
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
        if (followUpOrder.getStatus() == OrderStatus.PENDING_CONFIRM) {
            followUpOrder.setDeliveryDate(nextDispatchDate(followUpOrder, now.toLocalDate()));
            followUpOrder.setStatus(OrderStatus.CONFIRMED);
            ordersDAO.save(followUpOrder);
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

    private void queueDueCases(LocalDateTime now) {
        List<ExceptionCasesEntity> dueCases = exceptionCasesDAO.findDueForUpdate(
                ExceptionStatus.OPEN, now);
        dueCases.forEach(item -> item.setQueuedAt(now));
        exceptionCasesDAO.saveAll(dueCases);
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

    private ExceptionCaseResponse toResponse(ExceptionCasesEntity exceptionCase) {
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
