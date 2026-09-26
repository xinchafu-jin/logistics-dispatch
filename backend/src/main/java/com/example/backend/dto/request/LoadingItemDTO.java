package com.example.backend.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** 司機在倉庫逐項勾選並清點的商品。 */
public class LoadingItemDTO {

    @NotNull(message = "點交商品 ID 不能為空")
    @Positive(message = "點交商品 ID 必須大於 0")
    private Long orderItemId;

    @NotNull(message = "請勾選商品是否已核對")
    private Boolean checked;

    /** 不傳時代表清點數量與訂單相同；有短少時傳實際數量。 */
    @PositiveOrZero(message = "商品實點數量不能小於 0")
    private Integer loadedQuantity;

    @Size(max = 255, message = "商品點交備註不能超過 255 字元")
    private String notes;

    public Long getOrderItemId() {
        return orderItemId;
    }

    public void setOrderItemId(Long orderItemId) {
        this.orderItemId = orderItemId;
    }

    public Boolean getChecked() {
        return checked;
    }

    public void setChecked(Boolean checked) {
        this.checked = checked;
    }

    public Integer getLoadedQuantity() {
        return loadedQuantity;
    }

    public void setLoadedQuantity(Integer loadedQuantity) {
        this.loadedQuantity = loadedQuantity;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }
}
