package com.example.backend.dto.request;

import com.example.backend.constants.OrderStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import java.time.LocalDateTime;
import static com.example.backend.constants.ValidMsg.*;

public class OrdersDTO {

    private Long id;

    @NotBlank(message = ORDER_NUMBER_REQUIRED)
    @Size(max = 30, message = ORDER_NUMBER_MAX_LENGTH)
    private String orderNumber;

    @NotNull(message = ORDER_STORE_ID_REQUIRED)
    private Long storeId;

    @Size(max = 100, message = ORDER_VENDOR_MAX_LENGTH)
    private String sourceVendor;

    @Size(max = 255, message = ORDER_ITEM_DESCRIPTION_MAX_LENGTH)
    private String itemDescription;

    @NotNull(message = ORDER_BOX_COUNT_REQUIRED)
    @Min(value = 1, message = ORDER_BOX_COUNT_MIN)
    private Integer boxCount;

    private String notes;

    @NotNull(message = ORDER_DELIVERY_DATE_REQUIRED)
    private LocalDate deliveryDate;

    @NotNull(message = ORDER_STATUS_REQUIRED)
    private OrderStatus status;

    private Long assignedVehicleId;
    private Long assignedDriverId;
    private Integer sequence;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @NotNull(message = ORDER_WAREHOUSE_ID_REQUIRED)
    private Long warehouseId;

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

    public Long getWarehouseId() { return warehouseId; }
    public void setWarehouseId(Long warehouseId) { this.warehouseId = warehouseId; }
}