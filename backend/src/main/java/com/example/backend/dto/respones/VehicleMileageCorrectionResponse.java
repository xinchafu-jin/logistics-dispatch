package com.example.backend.dto.respones;

import com.example.backend.constants.MileageCorrectionField;

import java.time.LocalDateTime;

/** 車輛的一筆里程更正紀錄：誰、什麼時候、把哪個數字從多少改成多少、為什麼。 */
public class VehicleMileageCorrectionResponse {

    private Long id;
    private MileageCorrectionField field;
    private Integer oldKm;
    private Integer newKm;
    private String reason;
    /** 車在外面跑時一起改到出車讀數的那一趟；沒有就是 null */
    private Long mileageLogId;
    private String correctedBy;
    private LocalDateTime correctedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
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
