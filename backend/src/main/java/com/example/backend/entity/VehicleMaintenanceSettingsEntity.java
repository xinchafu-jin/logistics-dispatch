package com.example.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 全車共用的保養設定，只有 id = 1 這一列（V11 建表時就插好）。 */
@Entity
@Table(name = "vehicle_maintenance_settings")
public class VehicleMaintenanceSettingsEntity {

    public static final long SINGLE_ROW_ID = 1L;

    @Id
    private Long id = SINGLE_ROW_ID;

    /** 預估跑完這趟後，剩下多少公里以內要提醒（還沒超過不擋，只是提醒） */
    @Column(nullable = false)
    private Integer warningKm = 500;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Integer getWarningKm() {
        return warningKm;
    }

    public void setWarningKm(Integer warningKm) {
        this.warningKm = warningKm;
    }
}
