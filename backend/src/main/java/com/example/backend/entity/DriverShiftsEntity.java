package com.example.backend.entity;

import com.example.backend.constants.ShiftType;
import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

@Entity
@Table(name = "driver_shifts", uniqueConstraints = {
        @UniqueConstraint(name = "uk_driver_shifts_driver_date", columnNames = {"driver_id", "work_date"})
}, indexes = {
        @Index(name = "idx_driver_shifts_month_date", columnList = "schedule_month_id,work_date")
})
public class DriverShiftsEntity {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "schedule_month_id", nullable = false)
    private Long scheduleMonthId;

    @Column(name = "driver_id", nullable = false)
    private Long driverId;

    @Column(name = "work_date", nullable = false)
    private LocalDate workDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "shift_type", nullable = false, length = 20)
    private ShiftType shiftType = ShiftType.UNASSIGNED;

    @Column(name = "work_start")
    private LocalTime workStart;

    @Column(name = "work_end")
    private LocalTime workEnd;

    /** 相容既有資料庫的 NOT NULL 欄位；排班流程不再讀寫預排加班，一律存 0。 */
    @Deprecated
    @Column(name = "overtime_minutes", nullable = false)
    private Integer legacyOvertimeMinutes = 0;

    /** 主管最後一次修改班次時留下的原因，例如臨時請假。 */
    @Column(name = "change_reason", length = 255)
    private String changeReason;

    @Column(name = "last_modified_at", nullable = false)
    private LocalDateTime lastModifiedAt;

    /** 防止兩位主管同時修改時，後儲存的人無聲覆蓋前一人的內容。 */
    @Version
    @Column(nullable = false)
    private Long version;

    @PrePersist
    @PreUpdate
    private void updateModifiedTime() {
        lastModifiedAt = LocalDateTime.now(TAIPEI);
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

    public Long getVersion() {
        return version;
    }
}
