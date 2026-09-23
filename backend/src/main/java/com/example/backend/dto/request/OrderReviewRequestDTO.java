package com.example.backend.dto.request;

import com.example.backend.constants.OrderReviewAction;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public class OrderReviewRequestDTO {

    @NotNull(message = "訂單操作不能為空")
    private OrderReviewAction action;

    @Positive(message = "門市 ID 必須大於 0")
    private Long storeId;

    @Positive(message = "倉庫 ID 必須大於 0")
    private Long warehouseId;

    @Min(value = 1, message = "箱數至少為 1")
    private Integer boxCount;

    private LocalDate deliveryDate;

    @Size(max = 100, message = "來源商家不可超過 100 字")
    private String sourceVendor;

    @Size(max = 255, message = "內容物說明不可超過 255 字")
    private String itemDescription;

    @Size(max = 500, message = "備註不可超過 500 字")
    private String notes;

    @Size(max = 500, message = "原因不可超過 500 字")
    private String reason;

    public OrderReviewAction getAction() { return action; }
    public void setAction(OrderReviewAction action) { this.action = action; }
    public Long getStoreId() { return storeId; }
    public void setStoreId(Long storeId) { this.storeId = storeId; }
    public Long getWarehouseId() { return warehouseId; }
    public void setWarehouseId(Long warehouseId) { this.warehouseId = warehouseId; }
    public Integer getBoxCount() { return boxCount; }
    public void setBoxCount(Integer boxCount) { this.boxCount = boxCount; }
    public LocalDate getDeliveryDate() { return deliveryDate; }
    public void setDeliveryDate(LocalDate deliveryDate) { this.deliveryDate = deliveryDate; }
    public String getSourceVendor() { return sourceVendor; }
    public void setSourceVendor(String sourceVendor) { this.sourceVendor = sourceVendor; }
    public String getItemDescription() { return itemDescription; }
    public void setItemDescription(String itemDescription) { this.itemDescription = itemDescription; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
