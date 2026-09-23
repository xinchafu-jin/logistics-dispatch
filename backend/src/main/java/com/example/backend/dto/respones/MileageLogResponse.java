package com.example.backend.dto.respones;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 司機出車／收車里程結果。
 * 實際里程由人工登記的收車總里程減去出車總里程；系統里程由 GPS 軌跡與 OSRM 結算。
 * 舊 GPS 欄位暫時保留，避免既有前端失效。
 */
public class MileageLogResponse {

    private Long id;
    private Long driverId;
    private Long routeId;
    private Long vehicleId;
    private LocalDate date;
    private Integer startOdometer;
    private Integer endOdometer;
    private String startMileagePhotoUrl;
    private LocalDateTime startMileagePhotoRecordedAt;
    private String endMileagePhotoUrl;
    private LocalDateTime endMileagePhotoRecordedAt;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer actualDistance;
    private Long actualDurationMinutes;
    private Double systemDistanceKm;
    private Double gpsDistanceKm;
    private String gpsDistanceStatus;
    private LocalDateTime mileageSettledAt;
    private Integer vehicleCurrentOdometerKm;
    private Double vehicleSystemCumulativeMileageKm;
    private Double vehicleCumulativeMileageKm;

    public MileageLogResponse() {
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getDriverId() { return driverId; }
    public void setDriverId(Long driverId) { this.driverId = driverId; }

    public Long getRouteId() { return routeId; }
    public void setRouteId(Long routeId) { this.routeId = routeId; }

    public Long getVehicleId() { return vehicleId; }
    public void setVehicleId(Long vehicleId) { this.vehicleId = vehicleId; }

    public LocalDate getDate() { return date; }
    public void setDate(LocalDate date) { this.date = date; }

    public Integer getStartOdometer() { return startOdometer; }
    public void setStartOdometer(Integer startOdometer) { this.startOdometer = startOdometer; }

    public Integer getEndOdometer() { return endOdometer; }
    public void setEndOdometer(Integer endOdometer) { this.endOdometer = endOdometer; }

    public String getStartMileagePhotoUrl() { return startMileagePhotoUrl; }
    public void setStartMileagePhotoUrl(String startMileagePhotoUrl) {
        this.startMileagePhotoUrl = startMileagePhotoUrl;
    }

    public LocalDateTime getStartMileagePhotoRecordedAt() { return startMileagePhotoRecordedAt; }
    public void setStartMileagePhotoRecordedAt(LocalDateTime startMileagePhotoRecordedAt) {
        this.startMileagePhotoRecordedAt = startMileagePhotoRecordedAt;
    }

    public String getEndMileagePhotoUrl() { return endMileagePhotoUrl; }
    public void setEndMileagePhotoUrl(String endMileagePhotoUrl) {
        this.endMileagePhotoUrl = endMileagePhotoUrl;
    }

    public LocalDateTime getEndMileagePhotoRecordedAt() { return endMileagePhotoRecordedAt; }
    public void setEndMileagePhotoRecordedAt(LocalDateTime endMileagePhotoRecordedAt) {
        this.endMileagePhotoRecordedAt = endMileagePhotoRecordedAt;
    }

    public LocalDateTime getStartTime() { return startTime; }
    public void setStartTime(LocalDateTime startTime) { this.startTime = startTime; }

    public LocalDateTime getEndTime() { return endTime; }
    public void setEndTime(LocalDateTime endTime) { this.endTime = endTime; }

    public Integer getActualDistance() { return actualDistance; }
    public void setActualDistance(Integer actualDistance) { this.actualDistance = actualDistance; }

    public Long getActualDurationMinutes() { return actualDurationMinutes; }
    public void setActualDurationMinutes(Long actualDurationMinutes) {
        this.actualDurationMinutes = actualDurationMinutes;
    }

    public Double getSystemDistanceKm() { return systemDistanceKm; }
    public void setSystemDistanceKm(Double systemDistanceKm) {
        this.systemDistanceKm = systemDistanceKm;
    }

    public Double getGpsDistanceKm() { return gpsDistanceKm; }
    public void setGpsDistanceKm(Double gpsDistanceKm) { this.gpsDistanceKm = gpsDistanceKm; }

    public String getGpsDistanceStatus() { return gpsDistanceStatus; }
    public void setGpsDistanceStatus(String gpsDistanceStatus) {
        this.gpsDistanceStatus = gpsDistanceStatus;
    }

    public LocalDateTime getMileageSettledAt() { return mileageSettledAt; }
    public void setMileageSettledAt(LocalDateTime mileageSettledAt) {
        this.mileageSettledAt = mileageSettledAt;
    }

    public Integer getVehicleCurrentOdometerKm() { return vehicleCurrentOdometerKm; }
    public void setVehicleCurrentOdometerKm(Integer vehicleCurrentOdometerKm) {
        this.vehicleCurrentOdometerKm = vehicleCurrentOdometerKm;
    }

    public Double getVehicleSystemCumulativeMileageKm() {
        return vehicleSystemCumulativeMileageKm;
    }
    public void setVehicleSystemCumulativeMileageKm(Double vehicleSystemCumulativeMileageKm) {
        this.vehicleSystemCumulativeMileageKm = vehicleSystemCumulativeMileageKm;
    }

    public Double getVehicleCumulativeMileageKm() { return vehicleCumulativeMileageKm; }
    public void setVehicleCumulativeMileageKm(Double vehicleCumulativeMileageKm) {
        this.vehicleCumulativeMileageKm = vehicleCumulativeMileageKm;
    }
}
