package com.example.backend.entity;

import com.example.backend.constants.RouteLegLocationType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;

/**
 * 一趟配送中的單一路段系統里程。
 * 例如公司到第一間門市、上一間門市到下一間門市，以及最後一間門市回公司。
 */
@Entity
@Table(
        name = "route_leg_mileages",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_route_leg_mileage_log_sequence",
                columnNames = {"mileage_log_id", "sequence"}
        ),
        indexes = {
                @Index(name = "idx_route_leg_route_sequence", columnList = "routeId,sequence"),
                @Index(name = "idx_route_leg_driver_time", columnList = "driverId,startedAt,endedAt")
        }
)
public class RouteLegMileagesEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long routeId;

    @Column(nullable = false)
    private Long mileageLogId;

    @Column(nullable = false)
    private Long driverId;

    @Column(nullable = false)
    private Long vehicleId;

    @Column(nullable = false)
    private Integer sequence;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RouteLegLocationType fromType;

    @Column
    private Long fromStoreId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RouteLegLocationType toType;

    @Column
    private Long toStoreId;

    /** 到達門市的路段對應訂單；回公司路段為空。 */
    @Column
    private Long orderId;

    /** 到達門市時建立的交貨紀錄；回公司路段為空。 */
    @Column
    private Long deliveryRecordId;

    @Column(nullable = false)
    private LocalDateTime startedAt;

    @Column(nullable = false)
    private LocalDateTime endedAt;

    /** GPS 軌跡經過濾後，以 OSRM 道路距離計算的公里數。 */
    @Column
    private Double systemDistanceKm;

    @Column(nullable = false)
    private Integer gpsPointCount;

    @Column(nullable = false)
    private Integer acceptedSegmentCount;

    @Column(nullable = false, length = 50)
    private String calculationStatus;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getRouteId() { return routeId; }
    public void setRouteId(Long routeId) { this.routeId = routeId; }
    public Long getMileageLogId() { return mileageLogId; }
    public void setMileageLogId(Long mileageLogId) { this.mileageLogId = mileageLogId; }
    public Long getDriverId() { return driverId; }
    public void setDriverId(Long driverId) { this.driverId = driverId; }
    public Long getVehicleId() { return vehicleId; }
    public void setVehicleId(Long vehicleId) { this.vehicleId = vehicleId; }
    public Integer getSequence() { return sequence; }
    public void setSequence(Integer sequence) { this.sequence = sequence; }
    public RouteLegLocationType getFromType() { return fromType; }
    public void setFromType(RouteLegLocationType fromType) { this.fromType = fromType; }
    public Long getFromStoreId() { return fromStoreId; }
    public void setFromStoreId(Long fromStoreId) { this.fromStoreId = fromStoreId; }
    public RouteLegLocationType getToType() { return toType; }
    public void setToType(RouteLegLocationType toType) { this.toType = toType; }
    public Long getToStoreId() { return toStoreId; }
    public void setToStoreId(Long toStoreId) { this.toStoreId = toStoreId; }
    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }
    public Long getDeliveryRecordId() { return deliveryRecordId; }
    public void setDeliveryRecordId(Long deliveryRecordId) { this.deliveryRecordId = deliveryRecordId; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getEndedAt() { return endedAt; }
    public void setEndedAt(LocalDateTime endedAt) { this.endedAt = endedAt; }
    public Double getSystemDistanceKm() { return systemDistanceKm; }
    public void setSystemDistanceKm(Double systemDistanceKm) { this.systemDistanceKm = systemDistanceKm; }
    public Integer getGpsPointCount() { return gpsPointCount; }
    public void setGpsPointCount(Integer gpsPointCount) { this.gpsPointCount = gpsPointCount; }
    public Integer getAcceptedSegmentCount() { return acceptedSegmentCount; }
    public void setAcceptedSegmentCount(Integer acceptedSegmentCount) {
        this.acceptedSegmentCount = acceptedSegmentCount;
    }
    public String getCalculationStatus() { return calculationStatus; }
    public void setCalculationStatus(String calculationStatus) { this.calculationStatus = calculationStatus; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
