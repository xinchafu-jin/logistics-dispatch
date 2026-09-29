package com.example.backend.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** 全車共用的保養設定；小保、大保間隔和退役總里程是每台車自己設定（見 VehiclesDTO）。 */
public class VehicleMaintenanceSettingsDTO {

    /** 預估跑完這趟後剩下多少公里以內要提醒 */
    @NotNull(message = "請填提前提醒公里數")
    @Min(value = 0, message = "提前提醒公里數不能小於 0")
    private Integer warningKm;

    public Integer getWarningKm() {
        return warningKm;
    }

    public void setWarningKm(Integer warningKm) {
        this.warningKm = warningKm;
    }
}
