package com.example.backend.dto.respones;

import com.example.backend.constants.EmergencyLeaveStatus;
import com.example.backend.constants.RouteStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 司機與主管共用的上班中特殊事由申請回應。 */
public class EmergencyLeaveResponse {
    private final Long id;
    private final Long driverId;
    private final String driverName;
    private final LocalDate workDate;
    private final Long attendanceRecordId;
    private final Long routeId;
    private final RouteStatus routeStatus;
    private final Long vehicleId;
    private final String plateNumber;
    private final String reason;
    private final EmergencyLeaveStatus status;
    private final Long replacementDriverId;
    private final String replacementDriverName;
    private final int transferredOrderCount;
    private final boolean gpsOverrideGranted;
    private final LocalDateTime requestedAt;
    private final String reviewedBy;
    private final LocalDateTime reviewedAt;
    private final String rejectionReason;
    private final LocalDateTime routeReassignedAt;
    private final LocalDateTime clockedOutAt;

    public EmergencyLeaveResponse(
            Long id, Long driverId, String driverName, LocalDate workDate,
            Long attendanceRecordId, Long routeId, RouteStatus routeStatus,
            Long vehicleId, String plateNumber, String reason, EmergencyLeaveStatus status,
            Long replacementDriverId, String replacementDriverName, int transferredOrderCount,
            boolean gpsOverrideGranted, LocalDateTime requestedAt, String reviewedBy,
            LocalDateTime reviewedAt, String rejectionReason, LocalDateTime routeReassignedAt,
            LocalDateTime clockedOutAt
    ) {
        this.id = id;
        this.driverId = driverId;
        this.driverName = driverName;
        this.workDate = workDate;
        this.attendanceRecordId = attendanceRecordId;
        this.routeId = routeId;
        this.routeStatus = routeStatus;
        this.vehicleId = vehicleId;
        this.plateNumber = plateNumber;
        this.reason = reason;
        this.status = status;
        this.replacementDriverId = replacementDriverId;
        this.replacementDriverName = replacementDriverName;
        this.transferredOrderCount = transferredOrderCount;
        this.gpsOverrideGranted = gpsOverrideGranted;
        this.requestedAt = requestedAt;
        this.reviewedBy = reviewedBy;
        this.reviewedAt = reviewedAt;
        this.rejectionReason = rejectionReason;
        this.routeReassignedAt = routeReassignedAt;
        this.clockedOutAt = clockedOutAt;
    }

    public Long getId() { return id; }
    public Long getDriverId() { return driverId; }
    public String getDriverName() { return driverName; }
    public LocalDate getWorkDate() { return workDate; }
    public Long getAttendanceRecordId() { return attendanceRecordId; }
    public Long getRouteId() { return routeId; }
    public RouteStatus getRouteStatus() { return routeStatus; }
    public Long getVehicleId() { return vehicleId; }
    public String getPlateNumber() { return plateNumber; }
    public String getReason() { return reason; }
    public EmergencyLeaveStatus getStatus() { return status; }
    public Long getReplacementDriverId() { return replacementDriverId; }
    public String getReplacementDriverName() { return replacementDriverName; }
    public int getTransferredOrderCount() { return transferredOrderCount; }
    public boolean isGpsOverrideGranted() { return gpsOverrideGranted; }
    public LocalDateTime getRequestedAt() { return requestedAt; }
    public String getReviewedBy() { return reviewedBy; }
    public LocalDateTime getReviewedAt() { return reviewedAt; }
    public String getRejectionReason() { return rejectionReason; }
    public LocalDateTime getRouteReassignedAt() { return routeReassignedAt; }
    public LocalDateTime getClockedOutAt() { return clockedOutAt; }
}
