package com.example.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/** 訂單實際內容物；同一張訂單可以有多筆。 */
public class OrderItemDTO {

    private Long id;

    @Size(max = 50, message = "商品代碼不能超過 50 字元")
    private String productCode;

    @NotBlank(message = "商品名稱不能為空")
    @Size(max = 100, message = "商品名稱不能超過 100 字元")
    private String itemName;

    @NotNull(message = "商品數量不能為空")
    @Positive(message = "商品數量必須大於 0")
    private Integer expectedQuantity;

    @NotBlank(message = "商品單位不能為空")
    @Size(max = 20, message = "商品單位不能超過 20 字元")
    private String unit = "件";

    @Positive(message = "商品排序必須大於 0")
    private Integer sequence;

    @Size(max = 255, message = "商品備註不能超過 255 字元")
    private String notes;

    private Integer loadedQuantity;
    private LocalDateTime checkedAt;
    private Long checkedByDriverId;
    private String loadingNotes;
    private boolean loadingMismatchReported;

    public boolean isLoadingMismatchReported() { return loadingMismatchReported; }
    public void setLoadingMismatchReported(boolean value) { loadingMismatchReported = value; }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getProductCode() {
        return productCode;
    }

    public void setProductCode(String productCode) {
        this.productCode = productCode;
    }

    public String getItemName() {
        return itemName;
    }

    public void setItemName(String itemName) {
        this.itemName = itemName;
    }

    public Integer getExpectedQuantity() {
        return expectedQuantity;
    }

    public void setExpectedQuantity(Integer expectedQuantity) {
        this.expectedQuantity = expectedQuantity;
    }

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }

    public Integer getSequence() {
        return sequence;
    }

    public void setSequence(Integer sequence) {
        this.sequence = sequence;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public Integer getLoadedQuantity() {
        return loadedQuantity;
    }

    public void setLoadedQuantity(Integer loadedQuantity) {
        this.loadedQuantity = loadedQuantity;
    }

    public LocalDateTime getCheckedAt() {
        return checkedAt;
    }

    public void setCheckedAt(LocalDateTime checkedAt) {
        this.checkedAt = checkedAt;
    }

    public Long getCheckedByDriverId() {
        return checkedByDriverId;
    }

    public void setCheckedByDriverId(Long checkedByDriverId) {
        this.checkedByDriverId = checkedByDriverId;
    }

    public String getLoadingNotes() {
        return loadingNotes;
    }

    public void setLoadingNotes(String loadingNotes) {
        this.loadingNotes = loadingNotes;
    }
}
