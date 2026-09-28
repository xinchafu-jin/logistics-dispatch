package com.example.backend.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * 司機送出的出車前安全檢查。
 *
 * <p>15 項每一項都要回答：true＝正常，false＝異常，null＝漏填（驗證不過）。
 * 異常照樣可以送出，後端會存成「不通過」讓主管處理；能不能出車由後端判定，不是前端。</p>
 *
 * <p>odometer 是出車時行車紀錄器上的里程：通過時要用它記出車里程（MileageLogsService.start），所以通過才必填；
 * 不通過（例如行車紀錄器壞了）就不會出車，可以不填。</p>
 */
public class PreTripInspectionRequestDTO {

    @NotNull(message = "缺少要檢查的路線")
    @Positive(message = "路線編號不正確")
    private Long routeId;

    @NotNull(message = "請填寫酒測值")
    @DecimalMin(value = "0.00", message = "酒測值不能小於 0")
    @DecimalMax(value = "9.99", message = "酒測值不能超過 9.99")
    @Digits(integer = 1, fraction = 2, message = "酒測值最多到小數第二位")
    private BigDecimal alcoholMgL;

    @NotNull(message = "請檢查行車紀錄器")
    private Boolean dashcam;

    @NotNull(message = "請檢查引擎機油")
    private Boolean engineOil;

    @NotNull(message = "請檢查煞車油")
    private Boolean brakeFluid;

    @NotNull(message = "請檢查動力方向盤油")
    private Boolean powerSteeringFluid;

    @NotNull(message = "請檢查變速箱油")
    private Boolean transmissionOil;

    @NotNull(message = "請檢查燃油")
    private Boolean fuel;

    @NotNull(message = "請檢查冷卻水")
    private Boolean coolant;

    @NotNull(message = "請檢查電瓶水")
    private Boolean batteryWater;

    @NotNull(message = "請檢查雨刷水")
    private Boolean washerFluid;

    @NotNull(message = "請檢查胎壓")
    private Boolean tirePressure;

    @NotNull(message = "請檢查胎紋")
    private Boolean tireTread;

    @NotNull(message = "請檢查頭燈")
    private Boolean headlights;

    @NotNull(message = "請檢查方向燈")
    private Boolean turnSignals;

    @NotNull(message = "請檢查煞車燈")
    private Boolean brakeLights;

    @NotNull(message = "請檢查儀表板燈")
    private Boolean dashboardLights;

    /** 出車時行車紀錄器上的累計里程（km）；檢查通過時必填（後端檢查） */
    @PositiveOrZero(message = "行車紀錄器里程不能小於 0")
    private Integer odometer;

    /** 有異常時必填（後端檢查），全部正常可以不填 */
    @Size(max = 500, message = "異常說明不能超過 500 字")
    private String note;

    public Long getRouteId() {
        return routeId;
    }

    public void setRouteId(Long routeId) {
        this.routeId = routeId;
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

    public Integer getOdometer() {
        return odometer;
    }

    public void setOdometer(Integer odometer) {
        this.odometer = odometer;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }
}
