package com.example.backend.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 逐項點交直接回報不符；沒有數量讀數時不推測實點數量。 */
public record LoadingMismatchRequestDTO(
        @NotNull @Positive Long orderId,
        @Positive Long orderItemId,
        @Size(min = 1) List<@NotNull @Positive Long> orderItemIds,
        @Size(max = 500) String notes
) {
    /** 保留原本單一商品請求的相容性。 */
    public LoadingMismatchRequestDTO(Long orderId, Long orderItemId, String notes) {
        this(orderId, orderItemId, null, notes);
    }

    @JsonIgnore
    @AssertTrue(message = "請選擇至少一個點交不符商品，且勿同時傳送兩種商品欄位")
    public boolean isItemSelectionValid() {
        return orderItemId != null && orderItemIds == null
                || orderItemId == null && orderItemIds != null && !orderItemIds.isEmpty();
    }

    public List<Long> selectedItemIds() {
        if (!isItemSelectionValid()) {
            throw new IllegalArgumentException("請選擇至少一個點交不符商品，且勿同時傳送兩種商品欄位");
        }
        return orderItemId == null ? orderItemIds : List.of(orderItemId);
    }
}
