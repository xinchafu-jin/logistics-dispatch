package com.example.backend.entity;

import com.example.backend.constants.AttendanceStatus;
import com.example.backend.constants.AttendancePunctualityStatus;
import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "attendance_records", uniqueConstraints = {
        @UniqueConstraint(name = "uk_attendance_shift", columnNames = "driver_shift_id")
}, indexes = {
        @Index(name = "idx_attendance_driver_date", columnList = "driver_id,work_date")
})
public class AttendanceRecordsEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "driver_shift_id", nullable = false)
    private Long driverShiftId;

    @Column(name = "driver_id", nullable = false)
    private Long driverId;

    @Column(name = "work_date", nullable = false)
    private LocalDate workDate;

    @Column(name = "clock_in_at", nullable = false)
    private LocalDateTime clockInAt;

    @Column(name = "clock_out_at")
    private LocalDateTime clockOutAt;

    /** 每個班次只能由 false 變成 true 一次，重新登入也不會重置。 */
    @Column(name = "break_used", nullable = false)
    private Boolean breakUsed = false;

    @Column(name = "break_started_at")
    private LocalDateTime breakStartedAt;

    @Column(name = "break_ends_at")
    private LocalDateTime breakEndsAt;

    /** 表定下班後累積滿第一段 30 分鐘加班的時間點。 */
    @Column(name = "overtime_started_at")
    private LocalDateTime overtimeStartedAt;

    @Column(name = "regular_work_minutes", nullable = false)
    private Integer regularWorkMinutes = 0;

    @Column(name = "overtime_minutes", nullable = false)
    private Integer overtimeMinutes = 0;

    @Column(name = "total_work_minutes", nullable = false)
    private Integer totalWorkMinutes = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "punctuality_status", nullable = false, length = 30)
    private AttendancePunctualityStatus punctualityStatus = AttendancePunctualityStatus.ON_TIME;

    @Column(name = "late_minutes", nullable = false)
    private Integer lateMinutes = 0;

    /** 每位司機每月最多一筆 30 分鐘內遲到可標記為赦免。 */
    @Column(name = "late_excused", nullable = false)
    private Boolean lateExcused = false;

    /** 超過表定上班時間 30 分鐘，需另補請假單；打卡本身不會被阻擋。 */
    @Column(name = "leave_required", nullable = false)
    private Boolean leaveRequired = false;

    @Column(name = "leave_required_minutes", nullable = false)
    private Integer leaveRequiredMinutes = 0;

    @Column(name = "covered_leave_request_id")
    private Long coveredLeaveRequestId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AttendanceStatus status = AttendanceStatus.WORKING;

    @Version
    @Column(nullable = false)
    private Long version;

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

    public AttendanceStatus getStatus() {
        return status;
    }

    public void setStatus(AttendanceStatus status) {
        this.status = status;
    }

    public Long getVersion() {
        return version;
    }
}
