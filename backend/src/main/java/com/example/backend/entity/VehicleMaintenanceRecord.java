package com.example.backend.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** 大小保及一般維修獨立紀錄；只有完成大小保才重設對應基準。 */
@Entity
@Table(name = "vehicle_maintenance_records")
public class VehicleMaintenanceRecord {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(nullable = false) public Long vehicleId;
    @Column(nullable = false, length = 10) public String type;
    @Column(nullable = false, length = 12) public String status;
    /** 既有維修沒有送修資料時保留 null，不能猜日期或里程。 */
    public LocalDateTime sentAt;
    public Integer sentOdometerKm;
    public LocalDateTime completedAt;
    public Integer completedOdometerKm;
    public LocalDateTime cancelledAt;
    @Column(length = 100) public String recordedBy;
    @Column(length = 100) public String completedBy;
    /** 非進行中時為 null，資料庫 unique 保证一台車僅一筆進行中送修。 */
    @Column(unique = true) public Long activeVehicleId;
}
