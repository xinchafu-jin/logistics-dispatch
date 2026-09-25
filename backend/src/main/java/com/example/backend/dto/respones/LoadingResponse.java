package com.example.backend.dto.respones;

import com.example.backend.constants.OrderStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

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
    private final Integer checkedItemCount;
    private final Integer totalItemCount;
    private final Boolean itemChecklistCompleted;
    private final List<ItemResult> items;

    public LoadingResponse(
            Long orderId,
            OrderStatus orderStatus,
            LocalDateTime loadedAt,
            Long exceptionCaseId,
            Long followUpOrderId,
            String followUpOrderNumber,
            LocalDate followUpDeliveryDate
    ) {
        this(orderId, orderStatus, loadedAt, exceptionCaseId, followUpOrderId,
                followUpOrderNumber, followUpDeliveryDate, 0, 0, true, List.of());
    }

    public LoadingResponse(
            Long orderId,
            OrderStatus orderStatus,
            LocalDateTime loadedAt,
            Long exceptionCaseId,
            Long followUpOrderId,
            String followUpOrderNumber,
            LocalDate followUpDeliveryDate,
            Integer checkedItemCount,
            Integer totalItemCount,
            Boolean itemChecklistCompleted,
            List<ItemResult> items
    ) {
        this.orderId = orderId;
        this.orderStatus = orderStatus;
        this.loadedAt = loadedAt;
        this.exceptionCaseId = exceptionCaseId;
        this.followUpOrderId = followUpOrderId;
        this.followUpOrderNumber = followUpOrderNumber;
        this.followUpDeliveryDate = followUpDeliveryDate;
        this.checkedItemCount = checkedItemCount;
        this.totalItemCount = totalItemCount;
        this.itemChecklistCompleted = itemChecklistCompleted;
        this.items = items;
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

    public Integer getCheckedItemCount() {
        return checkedItemCount;
    }

    public Integer getTotalItemCount() {
        return totalItemCount;
    }

    public Boolean getItemChecklistCompleted() {
        return itemChecklistCompleted;
    }

    public List<ItemResult> getItems() {
        return items;
    }

    public static class ItemResult {

        private final Long orderItemId;
        private final String itemName;
        private final Integer expectedQuantity;
        private final Integer loadedQuantity;
        private final String unit;
        private final Boolean matched;
        private final LocalDateTime checkedAt;
        private final String notes;

        public ItemResult(
                Long orderItemId,
                String itemName,
                Integer expectedQuantity,
                Integer loadedQuantity,
                String unit,
                Boolean matched,
                LocalDateTime checkedAt,
                String notes
        ) {
            this.orderItemId = orderItemId;
            this.itemName = itemName;
            this.expectedQuantity = expectedQuantity;
            this.loadedQuantity = loadedQuantity;
            this.unit = unit;
            this.matched = matched;
            this.checkedAt = checkedAt;
            this.notes = notes;
        }

        public Long getOrderItemId() {
            return orderItemId;
        }

        public String getItemName() {
            return itemName;
        }

        public Integer getExpectedQuantity() {
            return expectedQuantity;
        }

        public Integer getLoadedQuantity() {
            return loadedQuantity;
        }

        public String getUnit() {
            return unit;
        }

        public Boolean getMatched() {
            return matched;
        }

        public LocalDateTime getCheckedAt() {
            return checkedAt;
        }

        public String getNotes() {
            return notes;
        }
    }
}
