package com.example.backend.dto;

import com.example.backend.constans.OrderStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import java.time.LocalDateTime;

public class OrdersDTO {

    private Long id;

    @NotBlank(message = "訂單編號不能為空")
    @Size(max = 30, message = "訂單編號不能超過 30 字元")
    private String orderNumber;

    @NotNull(message = "門市 ID 不能為空")
    private Long storeId;

    @Size(max = 100, message = "來源商家長度不能超過 100 字元")
    private String sourceVendor;

    @Size(max = 255, message = "商品描述不能超過 255 字元")
    private String itemDescription;

    @NotNull(message = "箱數不能為空")
    @Min(value = 1, message = "箱數至少為 1")
    private Integer boxCount;

    @NotNull(message = "體積不能為空")
    @Min(value = 0, message = "體積不能小於 0")
    private Double volume;

    private String notes;

    @NotNull(message = "配送日期不能為空")
    private LocalDate deliveryDate;

    @NotNull(message = "訂單狀態不能為空")
    private OrderStatus status;

    private Long assignedVehicleId;
    private Long assignedDriverId;
    private Integer sequence;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // ===== Getter & Setter =====
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getOrderNumber() { return orderNumber; }
    public void setOrderNumber(String orderNumber) { this.orderNumber = orderNumber; }

    public Long getStoreId() { return storeId; }
    public void setStoreId(Long storeId) { this.storeId = storeId; }

    public String getSourceVendor() { return sourceVendor; }
    public void setSourceVendor(String sourceVendor) { this.sourceVendor = sourceVendor; }

    public String getItemDescription() { return itemDescription; }
    public void setItemDescription(String itemDescription) { this.itemDescription = itemDescription; }

    public Integer getBoxCount() { return boxCount; }
    public void setBoxCount(Integer boxCount) { this.boxCount = boxCount; }

    public Double getVolume() { return volume; }
    public void setVolume(Double volume) { this.volume = volume; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public LocalDate getDeliveryDate() { return deliveryDate; }
    public void setDeliveryDate(LocalDate deliveryDate) { this.deliveryDate = deliveryDate; }

    public OrderStatus getStatus() { return status; }
    public void setStatus(OrderStatus status) { this.status = status; }

    public Long getAssignedVehicleId() { return assignedVehicleId; }
    public void setAssignedVehicleId(Long assignedVehicleId) { this.assignedVehicleId = assignedVehicleId; }

    public Long getAssignedDriverId() { return assignedDriverId; }
    public void setAssignedDriverId(Long assignedDriverId) { this.assignedDriverId = assignedDriverId; }

    public Integer getSequence() { return sequence; }
    public void setSequence(Integer sequence) { this.sequence = sequence; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}