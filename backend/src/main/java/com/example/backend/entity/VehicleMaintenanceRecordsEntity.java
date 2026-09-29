package com.example.backend.entity;

import com.example.backend.constants.MaintenanceRecordStatus;
import com.example.backend.constants.MaintenanceRecordType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 一次送修：送小保、送大保、送維修時新增，完成或取消時補上時間。
 *
 * <p>activeVehicleId 在進行中時等於 vehicleId、結束就清成 null；資料庫對它有唯一鍵，
 * 所以一台車同一時間只會有一筆進行中的送修。</p>
 */
@Entity
@Table(name = "vehicle_maintenance_records")
public class VehicleMaintenanceRecordsEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long vehicleId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private MaintenanceRecordType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private MaintenanceRecordStatus status;

    private LocalDateTime sentAt;

    /** 送修當下的行車紀錄器里程；車輛還沒有里程時是 null，不猜 */
    private Integer sentOdometerKm;

    private LocalDateTime completedAt;

    private Integer completedOdometerKm;

    private LocalDateTime cancelledAt;

    @Column(length = 100)
    private String recordedBy;

    @Column(length = 100)
    private String completedBy;

    /** 進行中＝vehicleId，結束＝null（見類別說明） */
    @Column(unique = true)
    private Long activeVehicleId;

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

    public Long getActiveVehicleId() {
        return activeVehicleId;
    }

    public void setActiveVehicleId(Long activeVehicleId) {
        this.activeVehicleId = activeVehicleId;
    }
}
