package com.example.backend.dto.respones;

import com.example.backend.constants.AiActionType;

import java.time.LocalDate;

public class PendingActionResponse {
    private String id;          // 清單項目編號，加入清單時產生，用來刪除單一項目；跟下面的業務 id 無關
    private AiActionType type;
    private String summary;     // 給人看的文字，例如「指派司機 王小明 給 ABC-123」
    private LocalDate date;
    private Long warehouseId;
    private String warehouseName; // 只給畫面分組顯示用，執行不讀；PUBLISH_DAY 為 null（全部倉庫）
    private Long vehicleId;
    private Long driverId;
    private Long orderId;


    public PendingActionResponse() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
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

    public String getWarehouseName() {
        return warehouseName;
    }

    public void setWarehouseName(String warehouseName) {
        this.warehouseName = warehouseName;
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
