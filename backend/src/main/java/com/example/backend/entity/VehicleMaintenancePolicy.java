package com.example.backend.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;

/** 同噸位共用規則；車輛不保存間隔副本，修改後立即重算。 */
@Entity
@Table(name = "vehicle_maintenance_policies")
public class VehicleMaintenancePolicy {
    @Id @Column(precision = 6, scale = 2)
    public BigDecimal tonnage;
    @Column(nullable = false) public Integer minorIntervalKm;
    @Column(nullable = false) public Integer majorIntervalKm;
    @Column(nullable = false) public Integer retirementKm;
}
