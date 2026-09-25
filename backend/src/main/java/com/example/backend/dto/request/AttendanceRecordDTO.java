package com.example.backend.dto.request;

import com.example.backend.constants.AttendanceStatus;
import com.example.backend.constants.AttendancePunctualityStatus;

import java.math.BigDecimal;
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
    private LocalDateTime overtimeStartedAt;
    private Integer regularWorkMinutes;
    private Integer overtimeMinutes;
    private Integer totalWorkMinutes;
    private BigDecimal regularWorkHours;
    private BigDecimal overtimeHours;
    private BigDecimal totalWorkHours;
    private AttendanceStatus status;
    private Boolean gpsAllowed;
    private AttendancePunctualityStatus punctualityStatus;
    private Integer lateMinutes;
    private Boolean lateExcused;
    private Boolean leaveRequired;
    private Integer leaveRequiredMinutes;
    private Long coveredLeaveRequestId;

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

    public LocalDateTime getOvertimeStartedAt() {
        return overtimeStartedAt;
    }

    public void setOvertimeStartedAt(LocalDateTime overtimeStartedAt) {
        this.overtimeStartedAt = overtimeStartedAt;
    }

    public Integer getRegularWorkMinutes() {
        return regularWorkMinutes;
    }

    public void setRegularWorkMinutes(Integer regularWorkMinutes) {
        this.regularWorkMinutes = regularWorkMinutes;
    }

    public Integer getOvertimeMinutes() {
        return overtimeMinutes;
    }

    public void setOvertimeMinutes(Integer overtimeMinutes) {
        this.overtimeMinutes = overtimeMinutes;
    }

    public Integer getTotalWorkMinutes() {
        return totalWorkMinutes;
    }

    public void setTotalWorkMinutes(Integer totalWorkMinutes) {
        this.totalWorkMinutes = totalWorkMinutes;
    }

    public BigDecimal getRegularWorkHours() {
        return regularWorkHours;
    }

    public void setRegularWorkHours(BigDecimal regularWorkHours) {
        this.regularWorkHours = regularWorkHours;
    }

    public BigDecimal getOvertimeHours() {
        return overtimeHours;
    }

    public void setOvertimeHours(BigDecimal overtimeHours) {
        this.overtimeHours = overtimeHours;
    }

    public BigDecimal getTotalWorkHours() {
        return totalWorkHours;
    }

    public void setTotalWorkHours(BigDecimal totalWorkHours) {
        this.totalWorkHours = totalWorkHours;
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

    public AttendancePunctualityStatus getPunctualityStatus() { return punctualityStatus; }
    public void setPunctualityStatus(AttendancePunctualityStatus punctualityStatus) {
        this.punctualityStatus = punctualityStatus;
    }
    public Integer getLateMinutes() { return lateMinutes; }
    public void setLateMinutes(Integer lateMinutes) { this.lateMinutes = lateMinutes; }
    public Boolean getLateExcused() { return lateExcused; }
    public void setLateExcused(Boolean lateExcused) { this.lateExcused = lateExcused; }
    public Boolean getLeaveRequired() { return leaveRequired; }
    public void setLeaveRequired(Boolean leaveRequired) { this.leaveRequired = leaveRequired; }
    public Integer getLeaveRequiredMinutes() { return leaveRequiredMinutes; }
    public void setLeaveRequiredMinutes(Integer leaveRequiredMinutes) {
        this.leaveRequiredMinutes = leaveRequiredMinutes;
    }
    public Long getCoveredLeaveRequestId() { return coveredLeaveRequestId; }
    public void setCoveredLeaveRequestId(Long coveredLeaveRequestId) {
        this.coveredLeaveRequestId = coveredLeaveRequestId;
    }
}
