package com.example.backend.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

public class GpsPingDTO {

    private Long id;
    private Long driverId;

    @NotNull(message = "緯度不能為空")
    @DecimalMin(value = "-90.0", message = "緯度必須介於 -90 到 90")
    @DecimalMax(value = "90.0", message = "緯度必須介於 -90 到 90")
    private Double lat;

    @NotNull(message = "經度不能為空")
    @DecimalMin(value = "-180.0", message = "經度必須介於 -180 到 180")
    @DecimalMax(value = "180.0", message = "經度必須介於 -180 到 180")
    private Double lng;

    private LocalDateTime timestamp;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getDriverId() {
        return driverId;
    }

    public void setDriverId(Long driverId) {
        this.driverId = driverId;
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

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
    }
}
