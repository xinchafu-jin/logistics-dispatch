package com.example.backend.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 逐項點交直接回報不符；舊版未帶數量時仍不推測實點數量。 */
public record LoadingMismatchRequestDTO(
        @NotNull @Positive Long orderId,
        @Positive Long orderItemId,
        @Size(min = 1) List<@NotNull @Positive Long> orderItemIds,
        @Valid @Size(min = 1) List<LoadingMismatchItemDTO> items,
        @Size(max = 500) String notes
) {
    /** 保留原本單一商品請求的相容性。 */
    public LoadingMismatchRequestDTO(Long orderId, Long orderItemId, String notes) {
        this(orderId, orderItemId, null, null, notes);
    }

    /** 保留原本多商品請求的相容性。 */
    public LoadingMismatchRequestDTO(Long orderId, Long orderItemId, List<Long> orderItemIds, String notes) {
        this(orderId, orderItemId, orderItemIds, null, notes);
    }

    @JsonIgnore
    @AssertTrue(message = "請選擇至少一個點交不符商品，且僅能使用一種商品欄位")
    public boolean isItemSelectionValid() {
        int kinds = (orderItemId == null ? 0 : 1)
                + (orderItemIds == null ? 0 : 1)
                + (items == null ? 0 : 1);
        return kinds == 1
                && (orderItemIds == null || !orderItemIds.isEmpty())
                && (items == null || !items.isEmpty()
                    && (items.stream().noneMatch(item -> item != null && item.mismatchReported() != null)
                        || items.stream().anyMatch(item -> item != null && Boolean.TRUE.equals(item.mismatchReported()))));
    }

    public List<Long> selectedItemIds() {
        if (!isItemSelectionValid()) {
            throw new IllegalArgumentException("請選擇至少一個點交不符商品，且僅能使用一種商品欄位");
        }
        if (items != null) {
            boolean marked = items.stream().anyMatch(item -> item != null && item.mismatchReported() != null);
            return items.stream()
                    .filter(item -> !marked || item == null || Boolean.TRUE.equals(item.mismatchReported()))
                    .map(item -> item == null ? null : item.orderItemId()).toList();
        }
        return orderItemId == null ? orderItemIds : List.of(orderItemId);
    }
}
