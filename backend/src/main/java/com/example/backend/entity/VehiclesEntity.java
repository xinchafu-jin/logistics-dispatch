package com.example.backend.entity;

import com.example.backend.constants.VehicleStatus;
import jakarta.persistence.*;

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

    /** 目前的行車紀錄器里程（累計）；司機每次出車、收車時寫回。 */
    @Column
    private Integer currentOdometerKm;

    /**
     * 這台車自己的保養與退役規則：每跑多少公里小保、大保，以及退役總里程（不會因為保養而重設）。
     * 三個一起填或都不填，null＝還沒設定；主管隨時可以改，跟下面「只能補一次」的基準不同
     */
    @Column
    private Integer minorMaintenanceIntervalKm;

    @Column
    private Integer majorMaintenanceIntervalKm;

    @Column
    private Integer retirementKm;

    /** 上次小保完成時的行車紀錄器里程（小保基準）；null＝還沒設定 */
    @Column
    private Integer lastMinorMaintenanceKm;

    /** 上次大保完成時的行車紀錄器里程（大保基準）；null＝還沒設定 */
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

    public Integer getMinorMaintenanceIntervalKm() {
        return minorMaintenanceIntervalKm;
    }

    public void setMinorMaintenanceIntervalKm(Integer minorMaintenanceIntervalKm) {
        this.minorMaintenanceIntervalKm = minorMaintenanceIntervalKm;
    }

    public Integer getMajorMaintenanceIntervalKm() {
        return majorMaintenanceIntervalKm;
    }

    public void setMajorMaintenanceIntervalKm(Integer majorMaintenanceIntervalKm) {
        this.majorMaintenanceIntervalKm = majorMaintenanceIntervalKm;
    }

    public Integer getRetirementKm() {
        return retirementKm;
    }

    public void setRetirementKm(Integer retirementKm) {
        this.retirementKm = retirementKm;
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
