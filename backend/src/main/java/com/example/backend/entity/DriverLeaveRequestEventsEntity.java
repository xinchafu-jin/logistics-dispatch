package com.example.backend.entity;

import com.example.backend.constants.LeaveRequestEventType;
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
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 請假單的不可覆蓋稽核歷程。程式只提供新增與查詢，不提供修改或刪除 API。
 */
@Entity
@Table(name = "driver_leave_request_events", indexes = {
        @Index(name = "idx_leave_event_request_time", columnList = "leave_request_id,occurred_at")
})
public class DriverLeaveRequestEventsEntity {
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "leave_request_id", nullable = false)
    private Long leaveRequestId;

    @Column(name = "driver_id", nullable = false)
    private Long driverId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 30)
    private LeaveRequestEventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, length = 20)
    private LeaveSubmissionSource actorType;

    @Column(name = "actor_id", nullable = false)
    private Long actorId;

    @Column(name = "actor_account", nullable = false, length = 60)
    private String actorAccount;

    @Enumerated(EnumType.STRING)
    @Column(name = "old_status", length = 20)
    private LeaveRequestStatus oldStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_status", length = 20)
    private LeaveRequestStatus newStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "old_leave_type", length = 20)
    private LeaveType oldLeaveType;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_leave_type", length = 20)
    private LeaveType newLeaveType;

    @Column(nullable = false, length = 500)
    private String reason;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    @PrePersist
    private void initializeOccurredAt() {
        if (occurredAt == null) {
            occurredAt = LocalDateTime.now(TAIPEI);
        }
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getLeaveRequestId() { return leaveRequestId; }
    public void setLeaveRequestId(Long leaveRequestId) { this.leaveRequestId = leaveRequestId; }
    public Long getDriverId() { return driverId; }
    public void setDriverId(Long driverId) { this.driverId = driverId; }
    public LeaveRequestEventType getEventType() { return eventType; }
    public void setEventType(LeaveRequestEventType eventType) { this.eventType = eventType; }
    public LeaveSubmissionSource getActorType() { return actorType; }
    public void setActorType(LeaveSubmissionSource actorType) { this.actorType = actorType; }
    public Long getActorId() { return actorId; }
    public void setActorId(Long actorId) { this.actorId = actorId; }
    public String getActorAccount() { return actorAccount; }
    public void setActorAccount(String actorAccount) { this.actorAccount = actorAccount; }
    public LeaveRequestStatus getOldStatus() { return oldStatus; }
    public void setOldStatus(LeaveRequestStatus oldStatus) { this.oldStatus = oldStatus; }
    public LeaveRequestStatus getNewStatus() { return newStatus; }
    public void setNewStatus(LeaveRequestStatus newStatus) { this.newStatus = newStatus; }
    public LeaveType getOldLeaveType() { return oldLeaveType; }
    public void setOldLeaveType(LeaveType oldLeaveType) { this.oldLeaveType = oldLeaveType; }
    public LeaveType getNewLeaveType() { return newLeaveType; }
    public void setNewLeaveType(LeaveType newLeaveType) { this.newLeaveType = newLeaveType; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public LocalDateTime getOccurredAt() { return occurredAt; }
    public void setOccurredAt(LocalDateTime occurredAt) { this.occurredAt = occurredAt; }
}
