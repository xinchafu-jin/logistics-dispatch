package com.example.backend.dto.respones;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 司機出車／收車里程結果；保留原欄位並附加路線、車輛及 GPS 結算資訊。 */
public class MileageLogResponse {

    private Long id;
    private Long driverId;
    private Long routeId;
    private Long vehicleId;
    private LocalDate date;
    private Integer startOdometer;
    private Integer endOdometer;
    private String startMileagePhotoUrl;
    private String endMileagePhotoUrl;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer actualDistance;
    private Long actualDurationMinutes;
    private Double gpsDistanceKm;
    private Integer vehicleCurrentOdometerKm;
    private String gpsDistanceStatus;
    private LocalDateTime mileageSettledAt;
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

    public String getEndMileagePhotoUrl() { return endMileagePhotoUrl; }
    public void setEndMileagePhotoUrl(String endMileagePhotoUrl) {
        this.endMileagePhotoUrl = endMileagePhotoUrl;
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

    public Double getGpsDistanceKm() { return gpsDistanceKm; }
    public void setGpsDistanceKm(Double gpsDistanceKm) { this.gpsDistanceKm = gpsDistanceKm; }

    public Integer getVehicleCurrentOdometerKm() { return vehicleCurrentOdometerKm; }
    public void setVehicleCurrentOdometerKm(Integer vehicleCurrentOdometerKm) {
        this.vehicleCurrentOdometerKm = vehicleCurrentOdometerKm;
    }

    public String getGpsDistanceStatus() { return gpsDistanceStatus; }
    public void setGpsDistanceStatus(String gpsDistanceStatus) {
        this.gpsDistanceStatus = gpsDistanceStatus;
    }

    public LocalDateTime getMileageSettledAt() { return mileageSettledAt; }
    public void setMileageSettledAt(LocalDateTime mileageSettledAt) {
        this.mileageSettledAt = mileageSettledAt;
    }

    public Double getVehicleCumulativeMileageKm() { return vehicleCumulativeMileageKm; }
    public void setVehicleCumulativeMileageKm(Double vehicleCumulativeMileageKm) {
        this.vehicleCumulativeMileageKm = vehicleCumulativeMileageKm;
    }
}
