package com.example.backend.dto.respones;

import com.example.backend.constants.OrderStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 倉庫點交結果。箱數相符時 orderStatus 是 LOADED、有 loadedAt；
 * 不符時是 FAILED，並帶回異常單與補送單，loadedAt 為 null。
 */
public class LoadingResponse {

    private final Long orderId;
    private final OrderStatus orderStatus;
    private final LocalDateTime loadedAt;
    private final Long exceptionCaseId;
    private final Long followUpOrderId;
    private final String followUpOrderNumber;
    private final LocalDate followUpDeliveryDate;

    public LoadingResponse(
            Long orderId,
            OrderStatus orderStatus,
            LocalDateTime loadedAt,
            Long exceptionCaseId,
            Long followUpOrderId,
            String followUpOrderNumber,
            LocalDate followUpDeliveryDate
    ) {
        this.orderId = orderId;
        this.orderStatus = orderStatus;
        this.loadedAt = loadedAt;
        this.exceptionCaseId = exceptionCaseId;
        this.followUpOrderId = followUpOrderId;
        this.followUpOrderNumber = followUpOrderNumber;
        this.followUpDeliveryDate = followUpDeliveryDate;
    }

    public Long getOrderId() {
        return orderId;
    }

    public OrderStatus getOrderStatus() {
        return orderStatus;
    }

    public LocalDateTime getLoadedAt() {
        return loadedAt;
    }

    public Long getExceptionCaseId() {
        return exceptionCaseId;
    }

    public Long getFollowUpOrderId() {
        return followUpOrderId;
    }

    public String getFollowUpOrderNumber() {
        return followUpOrderNumber;
    }

    public LocalDate getFollowUpDeliveryDate() {
        return followUpDeliveryDate;
    }
}
