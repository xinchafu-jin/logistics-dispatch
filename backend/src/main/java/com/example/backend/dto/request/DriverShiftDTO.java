package com.example.backend.dto.request;

import com.example.backend.constants.ShiftType;
import com.fasterxml.jackson.annotation.JsonSetter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

public class DriverShiftDTO {

    private Long id;
    private Long scheduleMonthId;
    private Long driverId;
    private LocalDate workDate;
    private ShiftType shiftType;
    private LocalTime workStart;
    private LocalTime workEnd;
    private String changeReason;
    private LocalDateTime lastModifiedAt;
    private Long version;
    /** 舊版後台仍會傳此欄位；只為了拒絕非零預排加班，不回傳給前端。 */
    private Integer legacyOvertimeMinutes;

    @JsonSetter("overtimeMinutes")
    public void setLegacyOvertimeMinutes(Integer legacyOvertimeMinutes) {
        this.legacyOvertimeMinutes = legacyOvertimeMinutes;
    }

    public Integer requestedLegacyOvertimeMinutes() {
        return legacyOvertimeMinutes;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getScheduleMonthId() {
        return scheduleMonthId;
    }

    public void setScheduleMonthId(Long scheduleMonthId) {
        this.scheduleMonthId = scheduleMonthId;
    }

    public Long getDriverId() {
        return driverId;
    }

    public void setDriverId(Long driverId) {
        this.driverId = driverId;
    }

    public LocalDate getWorkDate() {
        return workDate;
    }

    public void setWorkDate(LocalDate workDate) {
        this.workDate = workDate;
    }

    public ShiftType getShiftType() {
        return shiftType;
    }

    public void setShiftType(ShiftType shiftType) {
        this.shiftType = shiftType;
    }

    public LocalTime getWorkStart() {
        return workStart;
    }

    public void setWorkStart(LocalTime workStart) {
        this.workStart = workStart;
    }

    public LocalTime getWorkEnd() {
        return workEnd;
    }

    public void setWorkEnd(LocalTime workEnd) {
        this.workEnd = workEnd;
    }

    public String getChangeReason() {
        return changeReason;
    }

    public void setChangeReason(String changeReason) {
        this.changeReason = changeReason;
    }

    public LocalDateTime getLastModifiedAt() {
        return lastModifiedAt;
    }

    public void setLastModifiedAt(LocalDateTime lastModifiedAt) {
        this.lastModifiedAt = lastModifiedAt;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }
}
