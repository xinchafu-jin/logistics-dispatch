package com.example.backend.dto.request;

import com.example.backend.constants.VehicleStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Min;
import static com.example.backend.constants.ValidMsg.*;

public class VehiclesDTO {

    private Long id;

    @NotNull(message = VEHICLE_WAREHOUSE_ID_REQUIRED)
    @Min(value = 1, message = VEHICLE_WAREHOUSE_ID_MIN)
    private Long warehouseId;

    @NotBlank(message = VEHICLE_PLATE_REQUIRED)
    @Size(max = 20, message = VEHICLE_PLATE_MAX_LENGTH)
    private String plateNumber;

    @Size(max = 50, message = VEHICLE_TYPE_MAX_LENGTH)
    private String vehicleType;

    @NotNull(message = VEHICLE_CAPACITY_REQUIRED)
    @Min(value = 0, message = VEHICLE_CAPACITY_MIN)
    private Integer capacity;

    @Min(value = 0, message = VEHICLE_FUEL_CONSUMPTION_MIN)
    private Double fuelConsumption;

    /** 後端唯讀回傳欄位，新增或修改車輛時不會由前端覆寫。 */
    private Double cumulativeMileageKm;

    @NotNull(message = VEHICLE_STATUS_REQUIRED)
    private VehicleStatus status;

    // ===== Getter & Setter =====


    public Long getWarehouseId() {
        return warehouseId;
    }

    public void setWarehouseId(Long warehouseId) {
        this.warehouseId = warehouseId;
    }

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

    public Double getCumulativeMileageKm() { return cumulativeMileageKm; }
    public void setCumulativeMileageKm(Double cumulativeMileageKm) {
        this.cumulativeMileageKm = cumulativeMileageKm;
    }

    public VehicleStatus getStatus() { return status; }
    public void setStatus(VehicleStatus status) { this.status = status; }
}
