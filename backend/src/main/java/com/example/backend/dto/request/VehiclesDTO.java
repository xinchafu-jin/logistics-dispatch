package com.example.backend.dto.request;

import com.example.backend.constants.VehicleStatus;
import com.example.backend.dto.respones.VehicleMaintenanceSummary;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Min;
import java.math.BigDecimal;
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

    @DecimalMin("0.01")
    @Digits(integer = 4, fraction = 2)
    private BigDecimal tonnage;

    /** 只在建檔時接受初始值；修改車輛時不得覆寫實際里程或保養基準。 */
    @Min(0)
    private Integer currentOdometerKm;
    @Min(0)
    private Integer lastMinorMaintenanceKm;
    @Min(0)
    private Integer lastMajorMaintenanceKm;

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private VehicleMaintenanceSummary maintenance;

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

    public BigDecimal getTonnage() { return tonnage; }
    public void setTonnage(BigDecimal tonnage) { this.tonnage = tonnage; }
    public Integer getCurrentOdometerKm() { return currentOdometerKm; }
    public void setCurrentOdometerKm(Integer currentOdometerKm) { this.currentOdometerKm = currentOdometerKm; }
    public Integer getLastMinorMaintenanceKm() { return lastMinorMaintenanceKm; }
    public void setLastMinorMaintenanceKm(Integer lastMinorMaintenanceKm) { this.lastMinorMaintenanceKm = lastMinorMaintenanceKm; }
    public Integer getLastMajorMaintenanceKm() { return lastMajorMaintenanceKm; }
    public void setLastMajorMaintenanceKm(Integer lastMajorMaintenanceKm) { this.lastMajorMaintenanceKm = lastMajorMaintenanceKm; }
    public VehicleMaintenanceSummary getMaintenance() { return maintenance; }
    public void setMaintenance(VehicleMaintenanceSummary maintenance) { this.maintenance = maintenance; }
}
