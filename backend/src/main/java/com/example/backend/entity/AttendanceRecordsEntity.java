package com.example.backend.entity;

import com.example.backend.constants.AttendanceStatus;
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

    /** 超過班表下班時間 30 分鐘、正式進入加班的時間點。 */
    @Column(name = "overtime_started_at")
    private LocalDateTime overtimeStartedAt;

    @Column(name = "regular_work_minutes", nullable = false)
    private Integer regularWorkMinutes = 0;

    @Column(name = "overtime_minutes", nullable = false)
    private Integer overtimeMinutes = 0;

    @Column(name = "total_work_minutes", nullable = false)
    private Integer totalWorkMinutes = 0;

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
