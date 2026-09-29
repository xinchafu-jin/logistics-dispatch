package com.example.backend.dto.request;

import com.example.backend.constants.OrderStatus;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static com.example.backend.constants.ValidMsg.*;

public class OrdersDTO {

    private Long id;

    /** 無人簽收重送單仍在等系統隔日 06:00 自動送待排；唯讀狀態。 */
    private boolean awaitingAutomaticDispatch;

    /** 後續訂單仍須在異常中心確認；一般審單入口不可先行核准。 */
    private boolean awaitingExceptionReview;

    @NotBlank(message = ORDER_NUMBER_REQUIRED)
    @Size(max = 30, message = ORDER_NUMBER_MAX_LENGTH)
    private String orderNumber;

    @NotNull(message = ORDER_STORE_ID_REQUIRED)
    @Positive(message = ORDER_STORE_ID_POSITIVE)
    private Long storeId;

    @Size(max = 100, message = ORDER_VENDOR_MAX_LENGTH)
    private String sourceVendor;

    @Size(max = 255, message = ORDER_ITEM_DESCRIPTION_MAX_LENGTH)
    private String itemDescription;

    /** 實際內容物明細；舊訂單可維持 null，新的訂單可傳多筆商品。 */
    @Valid
    private List<OrderItemDTO> items;

    @NotNull(message = ORDER_BOX_COUNT_REQUIRED)
    @Min(value = 1, message = ORDER_BOX_COUNT_MIN)
    private Integer boxCount;

    @Size(max = 500, message = ORDER_NOTES_MAX_LENGTH)
    private String notes;

    @NotNull(message = ORDER_DELIVERY_DATE_REQUIRED)
    private LocalDate deliveryDate;

    @NotNull(message = ORDER_STATUS_REQUIRED)
    private OrderStatus status;

    @Positive(message = ORDER_ASSIGNED_VEHICLE_ID_POSITIVE)
    private Long assignedVehicleId;

    @Positive(message = ORDER_ASSIGNED_DRIVER_ID_POSITIVE)
    private Long assignedDriverId;

    @Positive(message = ORDER_SEQUENCE_POSITIVE)
    private Integer sequence;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @NotNull(message = ORDER_WAREHOUSE_ID_REQUIRED)
    @Positive(message = ORDER_WAREHOUSE_ID_POSITIVE)
    private Long warehouseId;

    // ===== Getter & Setter =====
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public boolean isAwaitingAutomaticDispatch() { return awaitingAutomaticDispatch; }
    public void setAwaitingAutomaticDispatch(boolean awaitingAutomaticDispatch) {
        this.awaitingAutomaticDispatch = awaitingAutomaticDispatch;
    }

    public boolean isAwaitingExceptionReview() { return awaitingExceptionReview; }
    public void setAwaitingExceptionReview(boolean awaitingExceptionReview) {
        this.awaitingExceptionReview = awaitingExceptionReview;
    }

    public String getOrderNumber() { return orderNumber; }
    public void setOrderNumber(String orderNumber) { this.orderNumber = orderNumber; }

    public Long getStoreId() { return storeId; }
    public void setStoreId(Long storeId) { this.storeId = storeId; }

    public String getSourceVendor() { return sourceVendor; }
    public void setSourceVendor(String sourceVendor) { this.sourceVendor = sourceVendor; }

    public String getItemDescription() { return itemDescription; }
    public void setItemDescription(String itemDescription) { this.itemDescription = itemDescription; }

    public List<OrderItemDTO> getItems() { return items; }
    public void setItems(List<OrderItemDTO> items) { this.items = items; }

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
