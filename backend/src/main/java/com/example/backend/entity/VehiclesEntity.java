package com.example.backend.entity;

import com.example.backend.constants.VehicleStatus;
import jakarta.persistence.*;

import java.math.BigDecimal;

@Entity
@Table(name = "vehicles")
public class VehiclesEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Long warehouseId;
    @Column(nullable = false, unique = true, length = 20)
    private String plateNumber;

    @Column(length = 50)
    private String vehicleType;

    /**
     * 可裝箱數，容量單位統一用「箱」
     */
    @Column(nullable = false)
    private Integer capacity;

    /**
     * 平均油耗（公里／公升）
     */
    @Column
    private Double fuelConsumption;

    /** 車輛儀表板顯示的累積總里程。 */
    @Column
    private Integer currentOdometerKm;

    /** 車輛噸位，對應同噸位共用的保養規則。 */
    @Column(precision = 6, scale = 2)
    private BigDecimal tonnage;

    /** 最近完成小保／大保時的實際儀表讀數，不使用 GPS 預估里程。 */
    @Column
    private Integer lastMinorMaintenanceKm;

    @Column
    private Integer lastMajorMaintenanceKm;

    /**
     * 車輛自加入系統後，以每趟 GPS 道路距離結算的永久累積里程（公里）。
     */
    @Column(nullable = false)
    private Double cumulativeMileageKm = 0.0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private VehicleStatus status = VehicleStatus.AVAILABLE;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getPlateNumber() {
        return plateNumber;
    }

    public void setPlateNumber(String plateNumber) {
        this.plateNumber = plateNumber;
    }

    public String getVehicleType() {
        return vehicleType;
    }

    public void setVehicleType(String vehicleType) {
        this.vehicleType = vehicleType;
    }

    public Integer getCapacity() {
        return capacity;
    }

    public void setCapacity(Integer capacity) {
        this.capacity = capacity;
    }

    public Double getFuelConsumption() {
        return fuelConsumption;
    }

    public void setFuelConsumption(Double fuelConsumption) {
        this.fuelConsumption = fuelConsumption;
    }

    public Integer getCurrentOdometerKm() {
        return currentOdometerKm;
    }

    public void setCurrentOdometerKm(Integer currentOdometerKm) {
        this.currentOdometerKm = currentOdometerKm;
    }

    public BigDecimal getTonnage() {
        return tonnage;
    }

    public void setTonnage(BigDecimal tonnage) {
        this.tonnage = tonnage;
    }

    public Integer getLastMinorMaintenanceKm() {
        return lastMinorMaintenanceKm;
    }

    public void setLastMinorMaintenanceKm(Integer lastMinorMaintenanceKm) {
        this.lastMinorMaintenanceKm = lastMinorMaintenanceKm;
    }

    public Integer getLastMajorMaintenanceKm() {
        return lastMajorMaintenanceKm;
    }

    public void setLastMajorMaintenanceKm(Integer lastMajorMaintenanceKm) {
        this.lastMajorMaintenanceKm = lastMajorMaintenanceKm;
    }

    public Double getCumulativeMileageKm() {
        return cumulativeMileageKm;
    }

    public void setCumulativeMileageKm(Double cumulativeMileageKm) {
        this.cumulativeMileageKm = cumulativeMileageKm;
    }

    public VehicleStatus getStatus() {
        return status;
    }

    public void setStatus(VehicleStatus status) {
        this.status = status;
    }

    public Long getWarehouseId() {
        return warehouseId;
    }

    public void setWarehouseId(Long warehouseId) {
        this.warehouseId = warehouseId;
    }
}
