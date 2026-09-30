package com.example.backend.dto.respones;

import java.time.LocalDate;

/** 司機端月曆：某一天已發布的派車結果（哪個倉庫、哪台車）。 */
public class DriverAssignmentResponse {

    private LocalDate date;
    private Long warehouseId;
    private String warehouseName;
    /** 路線還沒排車時是 null */
    private String vehiclePlateNumber;
    /** 車型是自由填寫的文字，可能是 null */
    private String vehicleType;

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

    public String getVehiclePlateNumber() {
        return vehiclePlateNumber;
    }

    public void setVehiclePlateNumber(String vehiclePlateNumber) {
        this.vehiclePlateNumber = vehiclePlateNumber;
    }

    public String getVehicleType() {
        return vehicleType;
    }

    public void setVehicleType(String vehicleType) {
        this.vehicleType = vehicleType;
    }
}
