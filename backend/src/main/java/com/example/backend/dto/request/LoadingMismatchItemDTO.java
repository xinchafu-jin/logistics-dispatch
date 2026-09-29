package com.example.backend.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/** 回報不符商品時由司機實際清點的數量。 */
public record LoadingMismatchItemDTO(
        @NotNull @Positive Long orderItemId,
        @NotNull @PositiveOrZero Integer loadedQuantity,
        Boolean mismatchReported
) {
    /** 舊版逐項請求沒有標示其餘商品，沿用所列商品都是不符的語意。 */
    public LoadingMismatchItemDTO(Long orderItemId, Integer loadedQuantity) {
        this(orderItemId, loadedQuantity, null);
    }
}
