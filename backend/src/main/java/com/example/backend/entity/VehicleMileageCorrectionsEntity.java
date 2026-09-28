package com.example.backend.entity;

import com.example.backend.constants.MileageCorrectionField;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/** 主管更正一個里程數字的紀錄：一次更正改到幾個數字就有幾筆，原因和時間相同。 */
@Entity
@Table(name = "vehicle_mileage_corrections")
public class VehicleMileageCorrectionsEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long vehicleId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MileageCorrectionField field;

    /** 改之前的值；原本是空的就是 null */
    private Integer oldKm;

    @Column(nullable = false)
    private Integer newKm;

    @Column(nullable = false, length = 200)
    private String reason;

    /** 車在外面跑時更正目前里程，一起改到的那一趟 */
    private Long mileageLogId;

    @Column(length = 100)
    private String correctedBy;

    @Column(nullable = false)
    private LocalDateTime correctedAt;

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

    public MileageCorrectionField getField() {
        return field;
    }

    public void setField(MileageCorrectionField field) {
        this.field = field;
    }

    public Integer getOldKm() {
        return oldKm;
    }

    public void setOldKm(Integer oldKm) {
        this.oldKm = oldKm;
    }

    public Integer getNewKm() {
        return newKm;
    }

    public void setNewKm(Integer newKm) {
        this.newKm = newKm;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public Long getMileageLogId() {
        return mileageLogId;
    }

    public void setMileageLogId(Long mileageLogId) {
        this.mileageLogId = mileageLogId;
    }

    public String getCorrectedBy() {
        return correctedBy;
    }

    public void setCorrectedBy(String correctedBy) {
        this.correctedBy = correctedBy;
    }

    public LocalDateTime getCorrectedAt() {
        return correctedAt;
    }

    public void setCorrectedAt(LocalDateTime correctedAt) {
        this.correctedAt = correctedAt;
    }
}
