package com.example.backend.dto.respones;

import com.example.backend.constants.ExceptionStatus;
import com.example.backend.constants.ExceptionType;
import com.example.backend.constants.OrderStatus;
import com.example.backend.constants.OrderType;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 後台異常中心使用的配送異常明細。 */
public class ExceptionCaseResponse {

    private final Long id;
    private final ExceptionType type;
    private final ExceptionStatus status;
    private final String description;
    private final Long sourceOrderId;
    private final String sourceOrderNumber;
    private final OrderType sourceOrderType;
    private final Long deliveryRecordId;
    private final Integer expectedBoxCount;
    private final Integer deliveredBoxCount;
    private final Integer shortageBoxCount;
    private final Integer damagedBoxCount;
    private final Integer replacementRequiredBoxCount;
    private final Long followUpOrderId;
    private final String followUpOrderNumber;
    private final OrderType followUpOrderType;
    private final OrderStatus followUpOrderStatus;
    private final LocalDate followUpDeliveryDate;
    private final LocalDateTime reviewAvailableAt;
    private final LocalDateTime queuedAt;
    private final LocalDateTime createdAt;
    private final String handledBy;
    private final LocalDateTime handledAt;
    private final String resolution;

    public ExceptionCaseResponse(
            Long id,
            ExceptionType type,
            ExceptionStatus status,
            String description,
            Long sourceOrderId,
            String sourceOrderNumber,
            OrderType sourceOrderType,
            Long deliveryRecordId,
            Integer expectedBoxCount,
            Integer deliveredBoxCount,
            Integer shortageBoxCount,
            Integer damagedBoxCount,
            Integer replacementRequiredBoxCount,
            Long followUpOrderId,
            String followUpOrderNumber,
            OrderType followUpOrderType,
            OrderStatus followUpOrderStatus,
            LocalDate followUpDeliveryDate,
            LocalDateTime reviewAvailableAt,
            LocalDateTime queuedAt,
            LocalDateTime createdAt,
            String handledBy,
            LocalDateTime handledAt,
            String resolution
    ) {
        this.id = id;
        this.type = type;
        this.status = status;
        this.description = description;
        this.sourceOrderId = sourceOrderId;
        this.sourceOrderNumber = sourceOrderNumber;
        this.sourceOrderType = sourceOrderType;
        this.deliveryRecordId = deliveryRecordId;
        this.expectedBoxCount = expectedBoxCount;
        this.deliveredBoxCount = deliveredBoxCount;
        this.shortageBoxCount = shortageBoxCount;
        this.damagedBoxCount = damagedBoxCount;
        this.replacementRequiredBoxCount = replacementRequiredBoxCount;
        this.followUpOrderId = followUpOrderId;
        this.followUpOrderNumber = followUpOrderNumber;
        this.followUpOrderType = followUpOrderType;
        this.followUpOrderStatus = followUpOrderStatus;
        this.followUpDeliveryDate = followUpDeliveryDate;
        this.reviewAvailableAt = reviewAvailableAt;
        this.queuedAt = queuedAt;
        this.createdAt = createdAt;
        this.handledBy = handledBy;
        this.handledAt = handledAt;
        this.resolution = resolution;
    }

    public Long getId() {
        return id;
    }

    public ExceptionType getType() {
        return type;
    }

    public ExceptionStatus getStatus() {
        return status;
    }

    public String getDescription() {
        return description;
    }

    public Long getSourceOrderId() {
        return sourceOrderId;
    }

    public String getSourceOrderNumber() {
        return sourceOrderNumber;
    }

    public OrderType getSourceOrderType() {
        return sourceOrderType;
    }

    public Long getDeliveryRecordId() {
        return deliveryRecordId;
    }

    public Integer getExpectedBoxCount() {
        return expectedBoxCount;
    }

    public Integer getDeliveredBoxCount() {
        return deliveredBoxCount;
    }

    public Integer getShortageBoxCount() {
        return shortageBoxCount;
    }

    public Integer getDamagedBoxCount() {
        return damagedBoxCount;
    }

    public Integer getReplacementRequiredBoxCount() {
        return replacementRequiredBoxCount;
    }

    public Long getFollowUpOrderId() {
        return followUpOrderId;
    }

    public String getFollowUpOrderNumber() {
        return followUpOrderNumber;
    }

    public OrderType getFollowUpOrderType() {
        return followUpOrderType;
    }

    public OrderStatus getFollowUpOrderStatus() {
        return followUpOrderStatus;
    }

    public LocalDate getFollowUpDeliveryDate() {
        return followUpDeliveryDate;
    }

    public LocalDateTime getReviewAvailableAt() {
        return reviewAvailableAt;
    }

    public LocalDateTime getQueuedAt() {
        return queuedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public String getHandledBy() {
        return handledBy;
    }

    public LocalDateTime getHandledAt() {
        return handledAt;
    }

    public String getResolution() {
        return resolution;
    }
}
