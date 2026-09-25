package com.example.backend.entity;

import com.example.backend.constants.LeaveRequestStatus;
import com.example.backend.constants.LeaveSubmissionSource;
import com.example.backend.constants.LeaveType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

@Entity
@Table(name = "driver_leave_requests", indexes = {
        @Index(name = "idx_driver_leave_status_requested", columnList = "status,requested_at"),
        @Index(name = "idx_driver_leave_driver_date", columnList = "driver_id,work_date"),
        @Index(name = "idx_driver_leave_shift", columnList = "driver_shift_id")
})
public class DriverLeaveRequestsEntity {
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "driver_id", nullable = false)
    private Long driverId;

    @Column(name = "driver_shift_id", nullable = false)
    private Long driverShiftId;

    @Column(name = "work_date", nullable = false)
    private LocalDate workDate;

    /** 司機或主管建立紀錄時原本選擇的類別，後續修正時不覆蓋。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "requested_leave_type", nullable = false, length = 20)
    private LeaveType requestedLeaveType;

    /** 目前生效類別；主管可附理由修正。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "leave_type", nullable = false, length = 20)
    private LeaveType leaveType;

    @Column(name = "full_day", nullable = false)
    private Boolean fullDay;

    @Column(name = "leave_start")
    private LocalTime leaveStart;

    @Column(name = "leave_end")
    private LocalTime leaveEnd;

    @Column(name = "request_reason", nullable = false, length = 500)
    private String requestReason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LeaveRequestStatus status = LeaveRequestStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(name = "submission_source", nullable = false, length = 20)
    private LeaveSubmissionSource submissionSource = LeaveSubmissionSource.DRIVER;

    @Column(name = "decision_reason", length = 500)
    private String decisionReason;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "reviewed_by_admin_id")
    private Long reviewedByAdminId;

    @Column(name = "reviewed_by", length = 60)
    private String reviewedBy;

    @Column(name = "type_change_reason", length = 500)
    private String typeChangeReason;

    @Column(name = "type_changed_at")
    private LocalDateTime typeChangedAt;

    @Column(name = "type_changed_by_admin_id")
    private Long typeChangedByAdminId;

    @Column(name = "type_changed_by", length = 60)
    private String typeChangedBy;

    /** null 代表司機尚未讀取主管最新的審核或類別修正結果。 */
    @Column(name = "driver_read_at")
    private LocalDateTime driverReadAt;

    @Column(name = "last_updated_at", nullable = false)
    private LocalDateTime lastUpdatedAt;

    @Version
    @Column(nullable = false)
    private Long version;

    @PrePersist
    private void initializeTimestamps() {
        LocalDateTime now = LocalDateTime.now(TAIPEI);
        if (requestedAt == null) {
            requestedAt = now;
        }
        lastUpdatedAt = now;
    }

    @PreUpdate
    private void updateTimestamp() {
        lastUpdatedAt = LocalDateTime.now(TAIPEI);
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getDriverId() { return driverId; }
    public void setDriverId(Long driverId) { this.driverId = driverId; }
    public Long getDriverShiftId() { return driverShiftId; }
    public void setDriverShiftId(Long driverShiftId) { this.driverShiftId = driverShiftId; }
    public LocalDate getWorkDate() { return workDate; }
    public void setWorkDate(LocalDate workDate) { this.workDate = workDate; }
    public LeaveType getRequestedLeaveType() { return requestedLeaveType; }
    public void setRequestedLeaveType(LeaveType requestedLeaveType) { this.requestedLeaveType = requestedLeaveType; }
    public LeaveType getLeaveType() { return leaveType; }
    public void setLeaveType(LeaveType leaveType) { this.leaveType = leaveType; }
    public Boolean getFullDay() { return fullDay; }
    public void setFullDay(Boolean fullDay) { this.fullDay = fullDay; }
    public LocalTime getLeaveStart() { return leaveStart; }
    public void setLeaveStart(LocalTime leaveStart) { this.leaveStart = leaveStart; }
    public LocalTime getLeaveEnd() { return leaveEnd; }
    public void setLeaveEnd(LocalTime leaveEnd) { this.leaveEnd = leaveEnd; }
    public String getRequestReason() { return requestReason; }
    public void setRequestReason(String requestReason) { this.requestReason = requestReason; }
    public LeaveRequestStatus getStatus() { return status; }
    public void setStatus(LeaveRequestStatus status) { this.status = status; }
    public LeaveSubmissionSource getSubmissionSource() { return submissionSource; }
    public void setSubmissionSource(LeaveSubmissionSource submissionSource) { this.submissionSource = submissionSource; }
    public String getDecisionReason() { return decisionReason; }
    public void setDecisionReason(String decisionReason) { this.decisionReason = decisionReason; }
    public LocalDateTime getRequestedAt() { return requestedAt; }
    public void setRequestedAt(LocalDateTime requestedAt) { this.requestedAt = requestedAt; }
    public LocalDateTime getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(LocalDateTime reviewedAt) { this.reviewedAt = reviewedAt; }
    public Long getReviewedByAdminId() { return reviewedByAdminId; }
    public void setReviewedByAdminId(Long reviewedByAdminId) { this.reviewedByAdminId = reviewedByAdminId; }
    public String getReviewedBy() { return reviewedBy; }
    public void setReviewedBy(String reviewedBy) { this.reviewedBy = reviewedBy; }
    public String getTypeChangeReason() { return typeChangeReason; }
    public void setTypeChangeReason(String typeChangeReason) { this.typeChangeReason = typeChangeReason; }
    public LocalDateTime getTypeChangedAt() { return typeChangedAt; }
    public void setTypeChangedAt(LocalDateTime typeChangedAt) { this.typeChangedAt = typeChangedAt; }
    public Long getTypeChangedByAdminId() { return typeChangedByAdminId; }
    public void setTypeChangedByAdminId(Long typeChangedByAdminId) { this.typeChangedByAdminId = typeChangedByAdminId; }
    public String getTypeChangedBy() { return typeChangedBy; }
    public void setTypeChangedBy(String typeChangedBy) { this.typeChangedBy = typeChangedBy; }
    public LocalDateTime getDriverReadAt() { return driverReadAt; }
    public void setDriverReadAt(LocalDateTime driverReadAt) { this.driverReadAt = driverReadAt; }
    public LocalDateTime getLastUpdatedAt() { return lastUpdatedAt; }
    public Long getVersion() { return version; }
}
