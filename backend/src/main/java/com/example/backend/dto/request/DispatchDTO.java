package com.example.backend.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;

import static com.example.backend.constants.ValidMsg.DISPATCH_DATE_REQUIRED;
import static com.example.backend.constants.ValidMsg.DISPATCH_VEHICLE_ID_REQUIRED;
import static com.example.backend.constants.ValidMsg.DISPATCH_VEHICLES_REQUIRED;
import static com.example.backend.constants.ValidMsg.DISPATCH_WAREHOUSE_REQUIRED;

public class DispatchDTO {

    @NotNull(message = DISPATCH_DATE_REQUIRED)
    private LocalDate date;

    @NotNull(message = DISPATCH_WAREHOUSE_REQUIRED)
    private Long warehouseId;

    @NotEmpty(message = DISPATCH_VEHICLES_REQUIRED)
    private List<@NotNull(message = DISPATCH_VEHICLE_ID_REQUIRED) Long> vehicleIds;

    public LocalDate getDate() {
        return date;
    }

    public void setDate(LocalDate date) {
        this.date = date;
    }

    public Long getWarehouseId() {
        return warehouseId;
    }

    public void setWarehouseId(Long warehouseId) {
        this.warehouseId = warehouseId;
    }

    public List<Long> getVehicleIds() {
        return vehicleIds;
    }

    public void setVehicleIds(List<Long> vehicleIds) {
        this.vehicleIds = vehicleIds;
    }
}
