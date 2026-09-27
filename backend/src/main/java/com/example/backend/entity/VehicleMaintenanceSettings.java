package com.example.backend.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "vehicle_maintenance_settings")
public class VehicleMaintenanceSettings {
    @Id public Long id = 1L;
    @Column(nullable = false) public Integer warningKm = 500;
}
