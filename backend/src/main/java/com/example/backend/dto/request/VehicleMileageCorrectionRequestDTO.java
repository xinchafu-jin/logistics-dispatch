package com.example.backend.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 主管更正行車紀錄器里程與保養基準。三個數字都可以不帶，不帶或跟現在一樣就是不改；
 * 至少要改到一個，而且一定要寫原因。見 VehiclesService.correctMileage
 */
public class VehicleMileageCorrectionRequestDTO {

    @Min(value = 0, message = "行車紀錄器里程不能小於 0")
    private Integer currentOdometerKm;

    @Min(value = 0, message = "小保基準不能小於 0")
    private Integer lastMinorMaintenanceKm;

    @Min(value = 0, message = "大保基準不能小於 0")
    private Integer lastMajorMaintenanceKm;

    @NotBlank(message = "請寫更正的原因")
    @Size(max = 200, message = "原因最多 200 字")
    private String reason;

    public Integer getCurrentOdometerKm() {
        return currentOdometerKm;
    }

    public void setCurrentOdometerKm(Integer currentOdometerKm) {
        this.currentOdometerKm = currentOdometerKm;
    }

    public Integer getLastMinorMaintenanceKm() {
        return lastMinorMaintenanceKm;
    }

    public void setLastMinorMaintenanceKm(Integer lastMinorMaintenanceKm) {
        this.lastMinorMaintenanceKm = lastMinorMaintenanceKm;
    }

    public Integer getLastMajorMaintenanceKm() {
        return lastMajorMaintenanceKm;
    }

    public void setLastMajorMaintenanceKm(Integer lastMajorMaintenanceKm) {
        this.lastMajorMaintenanceKm = lastMajorMaintenanceKm;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
