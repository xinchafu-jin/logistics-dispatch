package com.example.backend.dto.request;

import com.example.backend.constants.VehicleStatus;
import com.example.backend.dto.respones.VehicleMaintenanceSummaryResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
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

    /**
     * 這台車的保養與退役規則：小保間隔、大保間隔、退役總里程。
     * 三個一起填或都不填（都不填就算不出保養，只提醒、不擋），隨時可以改。見 VehiclesService
     */
    @Min(value = 1, message = "小保間隔要大於 0")
    private Integer minorMaintenanceIntervalKm;

    @Min(value = 1, message = "大保間隔要大於 0")
    private Integer majorMaintenanceIntervalKm;

    @Min(value = 1, message = "退役總里程要大於 0")
    private Integer retirementKm;

    /**
     * 目前的行車紀錄器里程、上次小保／大保時的行車紀錄器里程。
     * 新增車輛時可以填；已經有值的只能由出車、收車、保養完成更新，還是空的（舊車）可以補一次。見 VehiclesService
     */
    @Min(value = 0, message = "行車紀錄器里程不能小於 0")
    private Integer currentOdometerKm;

    @Min(value = 0, message = "小保基準不能小於 0")
    private Integer lastMinorMaintenanceKm;

    @Min(value = 0, message = "大保基準不能小於 0")
    private Integer lastMajorMaintenanceKm;

    /** 後端唯讀回傳：這台車目前的保養狀況 */
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private VehicleMaintenanceSummaryResponse maintenance;

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

    public Integer getMinorMaintenanceIntervalKm() { return minorMaintenanceIntervalKm; }
    public void setMinorMaintenanceIntervalKm(Integer minorMaintenanceIntervalKm) {
        this.minorMaintenanceIntervalKm = minorMaintenanceIntervalKm;
    }

    public Integer getMajorMaintenanceIntervalKm() { return majorMaintenanceIntervalKm; }
    public void setMajorMaintenanceIntervalKm(Integer majorMaintenanceIntervalKm) {
        this.majorMaintenanceIntervalKm = majorMaintenanceIntervalKm;
    }

    public Integer getRetirementKm() { return retirementKm; }
    public void setRetirementKm(Integer retirementKm) { this.retirementKm = retirementKm; }

    public Integer getCurrentOdometerKm() { return currentOdometerKm; }
    public void setCurrentOdometerKm(Integer currentOdometerKm) { this.currentOdometerKm = currentOdometerKm; }

    public Integer getLastMinorMaintenanceKm() { return lastMinorMaintenanceKm; }
    public void setLastMinorMaintenanceKm(Integer lastMinorMaintenanceKm) {
        this.lastMinorMaintenanceKm = lastMinorMaintenanceKm;
    }

    public Integer getLastMajorMaintenanceKm() { return lastMajorMaintenanceKm; }
    public void setLastMajorMaintenanceKm(Integer lastMajorMaintenanceKm) {
        this.lastMajorMaintenanceKm = lastMajorMaintenanceKm;
    }

    public VehicleMaintenanceSummaryResponse getMaintenance() { return maintenance; }
    public void setMaintenance(VehicleMaintenanceSummaryResponse maintenance) { this.maintenance = maintenance; }
}
