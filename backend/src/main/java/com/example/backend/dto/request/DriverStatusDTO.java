package com.example.backend.dto.request;

import jakarta.validation.constraints.NotNull;

import static com.example.backend.constants.ValidMsg.DRIVER_ACTIVE_REQUIRED;

public class DriverStatusDTO {

    @NotNull(message = DRIVER_ACTIVE_REQUIRED)
    private Boolean isActive;

    public Boolean getIsActive() {
        return isActive;
    }

    public void setIsActive(Boolean isActive) {
        this.isActive = isActive;
    }
}