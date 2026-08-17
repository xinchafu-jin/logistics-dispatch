package com.example.backend.dto.request;

import com.example.backend.constants.VehicleStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Min;

public class VehiclesDTO {

    private Long id;

    @NotBlank(message = "車牌號碼不能為空")
    @Size(max = 20, message = "車牌號碼不能超過 20 字元")
    private String plateNumber;

    @Size(max = 50, message = "車輛類型不能超過 50 字元")
    private String vehicleType;

    @NotNull(message = "可用容量不能為空")
    @Min(value = 0, message = "容量不能小於 0")
    private Integer capacity;

    @Min(value = 0, message = "平均油耗不能小於 0")
    private Double fuelConsumption;

    @NotNull(message = "車輛狀態不能為空")
    private VehicleStatus status;

    // ===== Getter & Setter =====
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getPlateNumber() { return plateNumber; }
    public void setPlateNumber(String plateNumber) { this.plateNumber = plateNumber; }

    public String getVehicleType() { return vehicleType; }
    public void setVehicleType(String vehicleType) { this.vehicleType = vehicleType; }

    public Integer getCapacity() { return capacity; }
    public void setCapacity(Integer capacity) { this.capacity = capacity; }

    public Double getFuelConsumption() { return fuelConsumption; }
    public void setFuelConsumption(Double fuelConsumption) { this.fuelConsumption = fuelConsumption; }

    public VehicleStatus getStatus() { return status; }
    public void setStatus(VehicleStatus status) { this.status = status; }
}