package com.example.backend.dto.respones;

import com.example.backend.constants.MaintenanceRecordStatus;
import com.example.backend.constants.MaintenanceRecordType;

import java.time.LocalDateTime;

/** 車輛的一筆送修紀錄（保養與維修歷史）。 */
public class VehicleMaintenanceRecordResponse {

    private Long id;
    private Long vehicleId;
    private MaintenanceRecordType type;
    private MaintenanceRecordStatus status;
    private LocalDateTime sentAt;
    private Integer sentOdometerKm;
    private LocalDateTime completedAt;
    private Integer completedOdometerKm;
    private LocalDateTime cancelledAt;
    private String recordedBy;
    private String completedBy;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getVehicleId() {
        return vehicleId;
    }

    public void setVehicleId(Long vehicleId) {
        this.vehicleId = vehicleId;
    }

    public MaintenanceRecordType getType() {
        return type;
    }

    public void setType(MaintenanceRecordType type) {
        this.type = type;
    }

    public MaintenanceRecordStatus getStatus() {
        return status;
    }

    public void setStatus(MaintenanceRecordStatus status) {
        this.status = status;
    }

    public LocalDateTime getSentAt() {
        return sentAt;
    }

    public void setSentAt(LocalDateTime sentAt) {
        this.sentAt = sentAt;
    }

    public Integer getSentOdometerKm() {
        return sentOdometerKm;
    }

    public void setSentOdometerKm(Integer sentOdometerKm) {
        this.sentOdometerKm = sentOdometerKm;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(LocalDateTime completedAt) {
        this.completedAt = completedAt;
    }

    public Integer getCompletedOdometerKm() {
        return completedOdometerKm;
    }

    public void setCompletedOdometerKm(Integer completedOdometerKm) {
        this.completedOdometerKm = completedOdometerKm;
    }

    public LocalDateTime getCancelledAt() {
        return cancelledAt;
    }

    public void setCancelledAt(LocalDateTime cancelledAt) {
        this.cancelledAt = cancelledAt;
    }

    public String getRecordedBy() {
        return recordedBy;
    }

    public void setRecordedBy(String recordedBy) {
        this.recordedBy = recordedBy;
    }

    public String getCompletedBy() {
        return completedBy;
    }

    public void setCompletedBy(String completedBy) {
        this.completedBy = completedBy;
    }
}
