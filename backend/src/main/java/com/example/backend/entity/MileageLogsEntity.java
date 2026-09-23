package com.example.backend.entity;

import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 里程紀錄。司機出車與收工各填一次里程表讀數。
 * 系統里程由該趟相鄰 GPS 點的 OSRM 道路距離累加。
 * 實際里程 = endOdometer - startOdometer。
 * 實際工時 = endTime - startTime
 */
@Entity
@Table(
        name = "mileage_logs",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_mileage_logs_driver_date",
                columnNames = {"driver_id", "date"}
        )
)
public class MileageLogsEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long driverId;

    /** 出車時綁定的路線；舊資料可能為空。 */
    @Column
    private Long routeId;

    /** 出車時綁定的車輛；舊資料可能為空。 */
    @Column
    private Long vehicleId;

    @Column(nullable = false)
    private LocalDate date;

    @Column
    private Integer startOdometer;

    @Column
    private Integer endOdometer;

    /** 本趟實際里程，固定為 endOdometer - startOdometer。 */
    @Column
    private Integer actualDistanceKm;

    @Column
    private LocalDateTime startTime;

    @Column
    private LocalDateTime endTime;

    /** 該趟經 GPS 與 OSRM 結算的系統公里數。 */
    @Column
    private Double gpsDistanceKm;

    /** COMPLETE、IN_PROGRESS，或 GPS 資料不足等結算狀態。 */
    @Column(length = 50)
    private String gpsDistanceStatus;

    /** 非空代表該趟里程已經且只會累加到車輛一次。 */
    @Column
    private LocalDateTime mileageSettledAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getDriverId() {
        return driverId;
    }

    public void setDriverId(Long driverId) {
        this.driverId = driverId;
    }

    public Long getRouteId() {
        return routeId;
    }

    public void setRouteId(Long routeId) {
        this.routeId = routeId;
    }

    public Long getVehicleId() {
        return vehicleId;
    }

    public void setVehicleId(Long vehicleId) {
        this.vehicleId = vehicleId;
    }

    public LocalDate getDate() {
        return date;
    }

    public void setDate(LocalDate date) {
        this.date = date;
    }

    public Integer getStartOdometer() {
        return startOdometer;
    }

    public void setStartOdometer(Integer startOdometer) {
        this.startOdometer = startOdometer;
    }

    public Integer getEndOdometer() {
        return endOdometer;
    }

    public void setEndOdometer(Integer endOdometer) {
        this.endOdometer = endOdometer;
    }

    public Integer getActualDistanceKm() {
        return actualDistanceKm;
    }

    public void setActualDistanceKm(Integer actualDistanceKm) {
        this.actualDistanceKm = actualDistanceKm;
    }

    public LocalDateTime getStartTime() {
        return startTime;
    }

    public void setStartTime(LocalDateTime startTime) {
        this.startTime = startTime;
    }

    public LocalDateTime getEndTime() {
        return endTime;
    }

    public void setEndTime(LocalDateTime endTime) {
        this.endTime = endTime;
    }

    public Double getGpsDistanceKm() {
        return gpsDistanceKm;
    }

    public void setGpsDistanceKm(Double gpsDistanceKm) {
        this.gpsDistanceKm = gpsDistanceKm;
    }

    public String getGpsDistanceStatus() {
        return gpsDistanceStatus;
    }

    public void setGpsDistanceStatus(String gpsDistanceStatus) {
        this.gpsDistanceStatus = gpsDistanceStatus;
    }

    public LocalDateTime getMileageSettledAt() {
        return mileageSettledAt;
    }

    public void setMileageSettledAt(LocalDateTime mileageSettledAt) {
        this.mileageSettledAt = mileageSettledAt;
    }
}
