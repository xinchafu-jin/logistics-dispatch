package com.example.backend.dto.respones;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 司機端看到的出車前安全檢查結果。
 *
 * <p>completed＝這組人、車、版本有沒有送過；passed＝最新一筆有沒有通過；
 * departed＝這位司機今天是否已經出車（檢查通過時同一個交易記下行車紀錄器里程）。
 * 還沒送過時 id、alcoholMgL、submittedAt 都是 null。</p>
 */
public class PreTripInspectionResponse {

    private Long id;
    private Long routeId;
    private Long vehicleId;
    private boolean completed;
    private boolean passed;
    private BigDecimal alcoholMgL;
    /** 異常項目的中文名稱，例如「煞車燈」；全部正常是空清單 */
    private List<String> abnormalItems = List.of();
    private String note;
    private boolean hasFaultPhoto;
    private boolean departed;
    /** 出車時行車紀錄器上的里程；還沒出車是 null */
    private Integer startOdometer;
    private LocalDateTime submittedAt;
    /** 給司機看的一句話：通過了可以做什麼、沒通過要找誰 */
    private String message;

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

    public Long getVehicleId() {
        return vehicleId;
    }

    public void setVehicleId(Long vehicleId) {
        this.vehicleId = vehicleId;
    }

    public boolean isCompleted() {
        return completed;
    }

    public void setCompleted(boolean completed) {
        this.completed = completed;
    }

    public boolean isPassed() {
        return passed;
    }

    public void setPassed(boolean passed) {
        this.passed = passed;
    }

    public BigDecimal getAlcoholMgL() {
        return alcoholMgL;
    }

    public void setAlcoholMgL(BigDecimal alcoholMgL) {
        this.alcoholMgL = alcoholMgL;
    }

    public List<String> getAbnormalItems() {
        return abnormalItems;
    }

    public void setAbnormalItems(List<String> abnormalItems) {
        this.abnormalItems = abnormalItems;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public boolean isHasFaultPhoto() {
        return hasFaultPhoto;
    }

    public void setHasFaultPhoto(boolean hasFaultPhoto) {
        this.hasFaultPhoto = hasFaultPhoto;
    }

    public boolean isDeparted() {
        return departed;
    }

    public void setDeparted(boolean departed) {
        this.departed = departed;
    }

    public Integer getStartOdometer() {
        return startOdometer;
    }

    public void setStartOdometer(Integer startOdometer) {
        this.startOdometer = startOdometer;
    }

    public LocalDateTime getSubmittedAt() {
        return submittedAt;
    }

    public void setSubmittedAt(LocalDateTime submittedAt) {
        this.submittedAt = submittedAt;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
