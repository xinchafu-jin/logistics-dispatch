package com.example.backend.entity;

import com.example.backend.constants.EmergencyLeaveStatus;
import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

@Entity
@Table(name = "emergency_leave_requests", indexes = {
        @Index(name = "idx_emergency_leave_status_requested", columnList = "status,requested_at"),
        @Index(name = "idx_emergency_leave_driver_date", columnList = "driver_id,work_date")
})
public class EmergencyLeaveRequestsEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "driver_id", nullable = false)
    private Long driverId;

    @Column(name = "work_date", nullable = false)
    private LocalDate workDate;

    @Column(name = "attendance_record_id", nullable = false)
    private Long attendanceRecordId;

    @Column(name = "route_id", nullable = false)
    private Long routeId;

    @Column(name = "vehicle_id", nullable = false)
    private Long vehicleId;

    @Column(nullable = false, length = 500)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EmergencyLeaveStatus status = EmergencyLeaveStatus.PENDING;

    @Column(name = "replacement_driver_id")
    private Long replacementDriverId;

    @Column(name = "transferred_order_count", nullable = false)
    private Integer transferredOrderCount = 0;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private LocalDateTime requestedAt;

    @Column(name = "reviewed_by", length = 60)
    private String reviewedBy;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    @Column(name = "route_reassigned_at")
    private LocalDateTime routeReassignedAt;

    @Column(name = "clocked_out_at")
    private LocalDateTime clockedOutAt;

    @Version
    @Column(nullable = false)
    private Long version;

    @PrePersist
    private void onCreate() {
        requestedAt = LocalDateTime.now(ZoneId.of("Asia/Taipei"));
    }

    public Long getId() { return id; }
    public Long getDriverId() { return driverId; }
    public void setDriverId(Long driverId) { this.driverId = driverId; }
    public LocalDate getWorkDate() { return workDate; }
    public void setWorkDate(LocalDate workDate) { this.workDate = workDate; }
    public Long getAttendanceRecordId() { return attendanceRecordId; }
    public void setAttendanceRecordId(Long attendanceRecordId) { this.attendanceRecordId = attendanceRecordId; }
    public Long getRouteId() { return routeId; }
    public void setRouteId(Long routeId) { this.routeId = routeId; }
    public Long getVehicleId() { return vehicleId; }
    public void setVehicleId(Long vehicleId) { this.vehicleId = vehicleId; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public EmergencyLeaveStatus getStatus() { return status; }
    public void setStatus(EmergencyLeaveStatus status) { this.status = status; }
    public Long getReplacementDriverId() { return replacementDriverId; }
    public void setReplacementDriverId(Long replacementDriverId) { this.replacementDriverId = replacementDriverId; }
    public Integer getTransferredOrderCount() { return transferredOrderCount; }
    public void setTransferredOrderCount(Integer transferredOrderCount) { this.transferredOrderCount = transferredOrderCount; }
    public LocalDateTime getRequestedAt() { return requestedAt; }
    public String getReviewedBy() { return reviewedBy; }
    public void setReviewedBy(String reviewedBy) { this.reviewedBy = reviewedBy; }
    public LocalDateTime getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(LocalDateTime reviewedAt) { this.reviewedAt = reviewedAt; }
    public String getRejectionReason() { return rejectionReason; }
    public void setRejectionReason(String rejectionReason) { this.rejectionReason = rejectionReason; }
    public LocalDateTime getRouteReassignedAt() { return routeReassignedAt; }
    public void setRouteReassignedAt(LocalDateTime routeReassignedAt) { this.routeReassignedAt = routeReassignedAt; }
    public LocalDateTime getClockedOutAt() { return clockedOutAt; }
    public void setClockedOutAt(LocalDateTime clockedOutAt) { this.clockedOutAt = clockedOutAt; }
}
