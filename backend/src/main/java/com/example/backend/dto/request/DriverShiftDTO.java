package com.example.backend.dto.request;

import com.example.backend.constants.ShiftType;

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
    private Integer overtimeMinutes;
    private String changeReason;
    private LocalDateTime lastModifiedAt;
    private Long version;

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

    public Integer getOvertimeMinutes() {
        return overtimeMinutes;
    }

    public void setOvertimeMinutes(Integer overtimeMinutes) {
        this.overtimeMinutes = overtimeMinutes;
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
