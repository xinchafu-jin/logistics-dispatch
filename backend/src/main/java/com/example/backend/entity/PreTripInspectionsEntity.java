package com.example.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 一次出車前安全檢查：司機每送一次就新增一筆，不覆蓋舊的。
 *
 * <p>同一條路線、同一位司機、同一台車、同一個路線版本底下，沒作廢的最新一筆決定能不能出車和點交。
 * 15 項檢查的 Boolean：true＝正常，false＝異常。</p>
 */
@Entity
@Table(name = "pre_trip_inspections")
public class PreTripInspectionsEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long routeId;

    @Column(nullable = false)
    private Long driverId;

    @Column(nullable = false)
    private Long vehicleId;

    /** 送出時的 routes.version；換車、換人後版本 +1，這筆就不再算數 */
    @Column(nullable = false)
    private Integer routeVersion;

    @Column(nullable = false)
    private LocalDate workDate;

    /** 呼氣酒精濃度 mg/L；系統規定 0.00 才能出車 */
    @Column(name = "alcohol_mg_l", nullable = false, precision = 3, scale = 2)
    private BigDecimal alcoholMgL;

    /** 行車紀錄器 */
    @Column(nullable = false)
    private Boolean dashcam;

    // ── 五油 ──
    @Column(nullable = false)
    private Boolean engineOil;

    @Column(nullable = false)
    private Boolean brakeFluid;

    @Column(nullable = false)
    private Boolean powerSteeringFluid;

    @Column(nullable = false)
    private Boolean transmissionOil;

    @Column(nullable = false)
    private Boolean fuel;

    // ── 三水 ──
    @Column(nullable = false)
    private Boolean coolant;

    @Column(nullable = false)
    private Boolean batteryWater;

    @Column(nullable = false)
    private Boolean washerFluid;

    // ── 二胎 ──
    @Column(nullable = false)
    private Boolean tirePressure;

    @Column(nullable = false)
    private Boolean tireTread;

    // ── 四燈 ──
    @Column(nullable = false)
    private Boolean headlights;

    @Column(nullable = false)
    private Boolean turnSignals;

    @Column(nullable = false)
    private Boolean brakeLights;

    /** 儀表板燈：有沒有異常警示燈亮著 */
    @Column(nullable = false)
    private Boolean dashboardLights;

    /** 有異常時的說明 */
    @Column(length = 500)
    private String note;

    /** 酒測器讀數照片的檔名（不是網址），讀取要經過本人才能呼叫的 API */
    @Column(nullable = false, length = 100)
    private String alcoholPhoto;

    /** 故障照片的檔名，選填 */
    @Column(length = 100)
    private String faultPhoto;

    @Column(nullable = false)
    private Boolean passed;

    @Column(nullable = false)
    private LocalDateTime submittedAt;

    /** 撤回發布時作廢；null＝有效 */
    private LocalDateTime invalidatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getRouteId() {
        return routeId;
    }

    public void setRouteId(Long routeId) {
        this.routeId = routeId;
    }

    public Long getDriverId() {
        return driverId;
    }

    public void setDriverId(Long driverId) {
        this.driverId = driverId;
    }

    public Long getVehicleId() {
        return vehicleId;
    }

    public void setVehicleId(Long vehicleId) {
        this.vehicleId = vehicleId;
    }

    public Integer getRouteVersion() {
        return routeVersion;
    }

    public void setRouteVersion(Integer routeVersion) {
        this.routeVersion = routeVersion;
    }

    public LocalDate getWorkDate() {
        return workDate;
    }

    public void setWorkDate(LocalDate workDate) {
        this.workDate = workDate;
    }

    public BigDecimal getAlcoholMgL() {
        return alcoholMgL;
    }

    public void setAlcoholMgL(BigDecimal alcoholMgL) {
        this.alcoholMgL = alcoholMgL;
    }

    public Boolean getDashcam() {
        return dashcam;
    }

    public void setDashcam(Boolean dashcam) {
        this.dashcam = dashcam;
    }

    public Boolean getEngineOil() {
        return engineOil;
    }

    public void setEngineOil(Boolean engineOil) {
        this.engineOil = engineOil;
    }

    public Boolean getBrakeFluid() {
        return brakeFluid;
    }

    public void setBrakeFluid(Boolean brakeFluid) {
        this.brakeFluid = brakeFluid;
    }

    public Boolean getPowerSteeringFluid() {
        return powerSteeringFluid;
    }

    public void setPowerSteeringFluid(Boolean powerSteeringFluid) {
        this.powerSteeringFluid = powerSteeringFluid;
    }

    public Boolean getTransmissionOil() {
        return transmissionOil;
    }

    public void setTransmissionOil(Boolean transmissionOil) {
        this.transmissionOil = transmissionOil;
    }

    public Boolean getFuel() {
        return fuel;
    }

    public void setFuel(Boolean fuel) {
        this.fuel = fuel;
    }

    public Boolean getCoolant() {
        return coolant;
    }

    public void setCoolant(Boolean coolant) {
        this.coolant = coolant;
    }

    public Boolean getBatteryWater() {
        return batteryWater;
    }

    public void setBatteryWater(Boolean batteryWater) {
        this.batteryWater = batteryWater;
    }

    public Boolean getWasherFluid() {
        return washerFluid;
    }

    public void setWasherFluid(Boolean washerFluid) {
        this.washerFluid = washerFluid;
    }

    public Boolean getTirePressure() {
        return tirePressure;
    }

    public void setTirePressure(Boolean tirePressure) {
        this.tirePressure = tirePressure;
    }

    public Boolean getTireTread() {
        return tireTread;
    }

    public void setTireTread(Boolean tireTread) {
        this.tireTread = tireTread;
    }

    public Boolean getHeadlights() {
        return headlights;
    }

    public void setHeadlights(Boolean headlights) {
        this.headlights = headlights;
    }

    public Boolean getTurnSignals() {
        return turnSignals;
    }

    public void setTurnSignals(Boolean turnSignals) {
        this.turnSignals = turnSignals;
    }

    public Boolean getBrakeLights() {
        return brakeLights;
    }

    public void setBrakeLights(Boolean brakeLights) {
        this.brakeLights = brakeLights;
    }

    public Boolean getDashboardLights() {
        return dashboardLights;
    }

    public void setDashboardLights(Boolean dashboardLights) {
        this.dashboardLights = dashboardLights;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public String getAlcoholPhoto() {
        return alcoholPhoto;
    }

    public void setAlcoholPhoto(String alcoholPhoto) {
        this.alcoholPhoto = alcoholPhoto;
    }

    public String getFaultPhoto() {
        return faultPhoto;
    }

    public void setFaultPhoto(String faultPhoto) {
        this.faultPhoto = faultPhoto;
    }

    public Boolean getPassed() {
        return passed;
    }

    public void setPassed(Boolean passed) {
        this.passed = passed;
    }

    public LocalDateTime getSubmittedAt() {
        return submittedAt;
    }

    public void setSubmittedAt(LocalDateTime submittedAt) {
        this.submittedAt = submittedAt;
    }

    public LocalDateTime getInvalidatedAt() {
        return invalidatedAt;
    }

    public void setInvalidatedAt(LocalDateTime invalidatedAt) {
        this.invalidatedAt = invalidatedAt;
    }
}
