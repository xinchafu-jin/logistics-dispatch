package com.example.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import static com.example.backend.constants.ValidMsg.*;

public class WarehousesDTO {

    private Long id;

    @NotBlank(message = WAREHOUSE_CODE_REQUIRED)
    @Size(max = 20, message = WAREHOUSE_CODE_MAX_LENGTH)
    private String warehouseCode;

    @NotBlank(message = WAREHOUSE_NAME_REQUIRED)
    @Size(max = 100, message = WAREHOUSE_NAME_MAX_LENGTH)
    private String name;

    @Size(max = 255, message = WAREHOUSE_ADDRESS_MAX_LENGTH)
    private String address;

    @NotNull(message = WAREHOUSE_LAT_REQUIRED)
    private Double lat;

    @NotNull(message = WAREHOUSE_LNG_REQUIRED)
    private Double lng;

    @Size(max = 30, message = WAREHOUSE_PHONE_MAX_LENGTH)
    private String phone;

    // 新增倉庫時，預設啟用
    private Boolean isActive = true;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getWarehouseCode() {
        return warehouseCode;
    }

    public void setWarehouseCode(String warehouseCode) {
        this.warehouseCode = warehouseCode;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public Double getLat() {
        return lat;
    }

    public void setLat(Double lat) {
        this.lat = lat;
    }

    public Double getLng() {
        return lng;
    }

    public void setLng(Double lng) {
        this.lng = lng;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public Boolean getIsActive() {
        return isActive;
    }

    public void setIsActive(Boolean isActive) {
        this.isActive = isActive;
    }
}