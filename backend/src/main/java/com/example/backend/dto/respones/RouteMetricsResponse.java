package com.example.backend.dto.respones;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 路線的預估與 GPS 軌跡估算結果；距離為公里、時間為分鐘。 */
public class RouteMetricsResponse {

    private Long routeId;
    private LocalDate date;
    private Long driverId;
    private Long vehicleId;
    private Double plannedKm;
    private Integer plannedDriveMinutes;
    private Integer plannedTotalMinutes;
    private Double plannedFuelLiters;
    private Double plannedFuelCost;
    private Double gpsEstimatedKm;
    private Double gpsEstimatedFuelLiters;
    private Double gpsEstimatedFuelCost;
    private Double pricePerLiter;
    private String mileageStatus;
    private String fuelStatus;
    private List<Leg> legs;
    private Double remainingKm;
    private Integer remainingDriveMinutes;
    private Long nextOrderId;
    private Long nextStoreId;
    private LocalDateTime estimatedNextArrivalAt;
    private LocalDateTime estimatedReturnAt;
    private LocalDateTime gpsTimestamp;
    private String liveEtaStatus;

    public RouteMetricsResponse() {
    }

    public Long getRouteId() {
        return routeId;
    }

    public void setRouteId(Long routeId) {
        this.routeId = routeId;
    }

    public LocalDate getDate() {
        return date;
    }

    public void setDate(LocalDate date) {
        this.date = date;
    }

    public Long getDriverId() {
        return driverId;
    }

    public void setDriverId(Long driverId) {
        this.driverId = driverId;
    }

    public Long getVehicleId() {
        return vehicleId;
    }

    public void setVehicleId(Long vehicleId) {
        this.vehicleId = vehicleId;
    }

    public Double getPlannedKm() {
        return plannedKm;
    }

    public void setPlannedKm(Double plannedKm) {
        this.plannedKm = plannedKm;
    }

    public Integer getPlannedDriveMinutes() {
        return plannedDriveMinutes;
    }

    public void setPlannedDriveMinutes(Integer plannedDriveMinutes) {
        this.plannedDriveMinutes = plannedDriveMinutes;
    }

    public Integer getPlannedTotalMinutes() {
        return plannedTotalMinutes;
    }

    public void setPlannedTotalMinutes(Integer plannedTotalMinutes) {
        this.plannedTotalMinutes = plannedTotalMinutes;
    }

    public Double getPlannedFuelLiters() {
        return plannedFuelLiters;
    }

    public void setPlannedFuelLiters(Double plannedFuelLiters) {
        this.plannedFuelLiters = plannedFuelLiters;
    }

    public Double getPlannedFuelCost() {
        return plannedFuelCost;
    }

    public void setPlannedFuelCost(Double plannedFuelCost) {
        this.plannedFuelCost = plannedFuelCost;
    }

    public Double getGpsEstimatedKm() {
        return gpsEstimatedKm;
    }

    public void setGpsEstimatedKm(Double gpsEstimatedKm) {
        this.gpsEstimatedKm = gpsEstimatedKm;
    }

    public Double getGpsEstimatedFuelLiters() {
        return gpsEstimatedFuelLiters;
    }

    public void setGpsEstimatedFuelLiters(Double gpsEstimatedFuelLiters) {
        this.gpsEstimatedFuelLiters = gpsEstimatedFuelLiters;
    }

    public Double getGpsEstimatedFuelCost() {
        return gpsEstimatedFuelCost;
    }

    public void setGpsEstimatedFuelCost(Double gpsEstimatedFuelCost) {
        this.gpsEstimatedFuelCost = gpsEstimatedFuelCost;
    }

    public Double getPricePerLiter() {
        return pricePerLiter;
    }

    public void setPricePerLiter(Double pricePerLiter) {
        this.pricePerLiter = pricePerLiter;
    }

    public String getMileageStatus() {
        return mileageStatus;
    }

    public void setMileageStatus(String mileageStatus) {
        this.mileageStatus = mileageStatus;
    }

    public String getFuelStatus() {
        return fuelStatus;
    }

    public void setFuelStatus(String fuelStatus) {
        this.fuelStatus = fuelStatus;
    }

    public List<Leg> getLegs() {
        return legs;
    }

    public void setLegs(List<Leg> legs) {
        this.legs = legs;
    }

    public Double getRemainingKm() { return remainingKm; }
    public void setRemainingKm(Double remainingKm) { this.remainingKm = remainingKm; }
    public Integer getRemainingDriveMinutes() { return remainingDriveMinutes; }
    public void setRemainingDriveMinutes(Integer remainingDriveMinutes) {
        this.remainingDriveMinutes = remainingDriveMinutes;
    }
    public Long getNextOrderId() { return nextOrderId; }
    public void setNextOrderId(Long nextOrderId) { this.nextOrderId = nextOrderId; }
    public Long getNextStoreId() { return nextStoreId; }
    public void setNextStoreId(Long nextStoreId) { this.nextStoreId = nextStoreId; }
    public LocalDateTime getEstimatedNextArrivalAt() { return estimatedNextArrivalAt; }
    public void setEstimatedNextArrivalAt(LocalDateTime estimatedNextArrivalAt) {
        this.estimatedNextArrivalAt = estimatedNextArrivalAt;
    }
    public LocalDateTime getEstimatedReturnAt() { return estimatedReturnAt; }
    public void setEstimatedReturnAt(LocalDateTime estimatedReturnAt) {
        this.estimatedReturnAt = estimatedReturnAt;
    }
    public LocalDateTime getGpsTimestamp() { return gpsTimestamp; }
    public void setGpsTimestamp(LocalDateTime gpsTimestamp) { this.gpsTimestamp = gpsTimestamp; }
    public String getLiveEtaStatus() { return liveEtaStatus; }
    public void setLiveEtaStatus(String liveEtaStatus) { this.liveEtaStatus = liveEtaStatus; }

    public static class Leg {
        private int sequence;
        private String fromName;
        private String toName;
        private Long orderId;
        private Long storeId;
        private Double distanceKm;
        private Double driveMinutes;
        private Double estimatedFuelLiters;
        private Double estimatedFuelCost;

        public Leg() {
        }

        public int getSequence() {
            return sequence;
        }

        public void setSequence(int sequence) {
            this.sequence = sequence;
        }

        public String getFromName() {
            return fromName;
        }

        public void setFromName(String fromName) {
            this.fromName = fromName;
        }

        public String getToName() {
            return toName;
        }

        public void setToName(String toName) {
            this.toName = toName;
        }

        public Long getOrderId() {
            return orderId;
        }

        public void setOrderId(Long orderId) {
            this.orderId = orderId;
        }

        public Long getStoreId() {
            return storeId;
        }

        public void setStoreId(Long storeId) {
            this.storeId = storeId;
        }

        public Double getDistanceKm() {
            return distanceKm;
        }

        public void setDistanceKm(Double distanceKm) {
            this.distanceKm = distanceKm;
        }

        public Double getDriveMinutes() {
            return driveMinutes;
        }

        public void setDriveMinutes(Double driveMinutes) {
            this.driveMinutes = driveMinutes;
        }

        public Double getEstimatedFuelLiters() {
            return estimatedFuelLiters;
        }

        public void setEstimatedFuelLiters(Double estimatedFuelLiters) {
            this.estimatedFuelLiters = estimatedFuelLiters;
        }

        public Double getEstimatedFuelCost() {
            return estimatedFuelCost;
        }

        public void setEstimatedFuelCost(Double estimatedFuelCost) {
            this.estimatedFuelCost = estimatedFuelCost;
        }
    }
}
