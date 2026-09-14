package com.example.backend.dto.respones;

import com.example.backend.constants.AiActionType;

import java.time.LocalDate;

public class PendingActionResponse {
    private AiActionType type;
    private String summary;     // 給人看的文字，例如「指派司機 王小明 給 ABC-123」
    private LocalDate date;
    private Long warehouseId;
    private Long vehicleId;
    private Long driverId;
    private Long orderId;


    public PendingActionResponse() {
    }

    public AiActionType getType() {
        return type;
    }

    public void setType(AiActionType type) {
        this.type = type;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public LocalDate getDate() {
        return date;
    }

    public void setDate(LocalDate date) {
        this.date = date;
    }

    public Long getWarehouseId() {
        return warehouseId;
    }

    public void setWarehouseId(Long warehouseId) {
        this.warehouseId = warehouseId;
    }

    public Long getVehicleId() {
        return vehicleId;
    }

    public void setVehicleId(Long vehicleId) {
        this.vehicleId = vehicleId;
    }

    public Long getDriverId() {
        return driverId;
    }

    public void setDriverId(Long driverId) {
        this.driverId = driverId;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }
}
