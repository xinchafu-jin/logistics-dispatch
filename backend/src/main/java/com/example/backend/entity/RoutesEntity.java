package com.example.backend.entity;

import com.example.backend.constants.RouteStatus;
import jakarta.persistence.*;

import java.time.LocalDate;

/**
 * 配送計畫：一台車某天的一條路線。
 * 本趟包含哪些訂單，由 OrdersEntity.routeId 指向此表，此處不存訂單清單。
 */
@Entity
@Table(name = "routes")
public class RoutesEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDate date;

    /** 出發與返回的倉庫（depot）*/
    @Column(nullable = false)
    private Long warehouseId;

    @Column(nullable = false)
    private Long vehicleId;

    @Column(nullable = false)
    private Long driverId;

    /** 總里程（公尺）*/
    @Column
    private Double totalDistance;

    /** 預估油耗成本 */
    @Column
    private Double estimatedFuelCost;

    /** 預估總工時（分鐘）*/
    @Column
    private Integer estimatedWorkMinutes;

    /** 平均裝載率（0~1）*/
    @Column
    private Double loadRate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RouteStatus status = RouteStatus.DRAFT;

    /** 發布後每次異動 +1 */
    @Column(nullable = false)
    private Integer version = 1;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
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

    public Double getTotalDistance() {
        return totalDistance;
    }

    public void setTotalDistance(Double totalDistance) {
        this.totalDistance = totalDistance;
    }

    public Double getEstimatedFuelCost() {
        return estimatedFuelCost;
    }

    public void setEstimatedFuelCost(Double estimatedFuelCost) {
        this.estimatedFuelCost = estimatedFuelCost;
    }

    public Integer getEstimatedWorkMinutes() {
        return estimatedWorkMinutes;
    }

    public void setEstimatedWorkMinutes(Integer estimatedWorkMinutes) {
        this.estimatedWorkMinutes = estimatedWorkMinutes;
    }

    public Double getLoadRate() {
        return loadRate;
    }

    public void setLoadRate(Double loadRate) {
        this.loadRate = loadRate;
    }

    public RouteStatus getStatus() {
        return status;
    }

    public void setStatus(RouteStatus status) {
        this.status = status;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }
}
