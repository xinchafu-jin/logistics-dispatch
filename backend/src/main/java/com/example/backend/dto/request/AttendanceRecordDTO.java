package com.example.backend.dto.request;

import com.example.backend.constants.AttendanceStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;

public class AttendanceRecordDTO {

    private Long id;
    private Long driverShiftId;
    private Long driverId;
    private LocalDate workDate;
    private LocalDateTime clockInAt;
    private LocalDateTime clockOutAt;
    private Boolean breakUsed;
    private LocalDateTime breakStartedAt;
    private LocalDateTime breakEndsAt;
    private Long remainingBreakSeconds;
    private AttendanceStatus status;
    private Boolean gpsAllowed;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getDriverShiftId() {
        return driverShiftId;
    }

    public void setDriverShiftId(Long driverShiftId) {
        this.driverShiftId = driverShiftId;
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

    public LocalDateTime getClockInAt() {
        return clockInAt;
    }

    public void setClockInAt(LocalDateTime clockInAt) {
        this.clockInAt = clockInAt;
    }

    public LocalDateTime getClockOutAt() {
        return clockOutAt;
    }

    public void setClockOutAt(LocalDateTime clockOutAt) {
        this.clockOutAt = clockOutAt;
    }

    public Boolean getBreakUsed() {
        return breakUsed;
    }

    public void setBreakUsed(Boolean breakUsed) {
        this.breakUsed = breakUsed;
    }

    public LocalDateTime getBreakStartedAt() {
        return breakStartedAt;
    }

    public void setBreakStartedAt(LocalDateTime breakStartedAt) {
        this.breakStartedAt = breakStartedAt;
    }

    public LocalDateTime getBreakEndsAt() {
        return breakEndsAt;
    }

    public void setBreakEndsAt(LocalDateTime breakEndsAt) {
        this.breakEndsAt = breakEndsAt;
    }

    public Long getRemainingBreakSeconds() {
        return remainingBreakSeconds;
    }

    public void setRemainingBreakSeconds(Long remainingBreakSeconds) {
        this.remainingBreakSeconds = remainingBreakSeconds;
    }

    public AttendanceStatus getStatus() {
        return status;
    }

    public void setStatus(AttendanceStatus status) {
        this.status = status;
    }

    public Boolean getGpsAllowed() {
        return gpsAllowed;
    }

    public void setGpsAllowed(Boolean gpsAllowed) {
        this.gpsAllowed = gpsAllowed;
    }
}
