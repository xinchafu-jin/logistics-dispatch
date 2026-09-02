package com.example.backend.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public class MileageRequestDTO {

    @NotNull(message = "里程表讀數不能為空")
    @PositiveOrZero(message = "里程表讀數不能小於 0")
    private Integer odometer;

    public Integer getOdometer() {
        return odometer;
    }

    public void setOdometer(Integer odometer) {
        this.odometer = odometer;
    }
}
