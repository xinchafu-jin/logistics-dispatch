package com.example.backend.dto.request;

import jakarta.validation.constraints.PositiveOrZero;

public class MileageRequestDTO {

    /** 行車紀錄器上顯示的累計里程（km）；實際公里＝收車讀數－出車讀數 */
    @PositiveOrZero(message = "行車紀錄器里程不能小於 0")
    private Integer odometer;

    public Integer getOdometer() {
        return odometer;
    }

    public void setOdometer(Integer odometer) {
        this.odometer = odometer;
    }
}
