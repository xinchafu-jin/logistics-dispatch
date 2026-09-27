package com.example.backend.entity;

import com.example.backend.constants.RouteDeviationEndReason;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 一次偏離預定路線：確定偏離時新增，升級時補 escalatedAt，結束時補 endedAt 與 endReason。
 *
 * <p>endedAt 是 null 就是進行中；escalatedAt 有值是「警報」、沒有是「提示」。
 * 「連續第幾筆」的暫時計數不在這裡，在記憶體的 RouteDeviationTracker。</p>
 */
@Entity
@Table(name = "route_deviations")
public class RouteDeviationsEntity {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long routeId;

    @Column(nullable = false)
    private Long driverId;

    /** 偏離的是哪一段，對照 RoutePlannedLegsEntity.sequence */
    @Column(nullable = false)
    private Integer legSequence;

    /** 確定偏離的那一筆 GPS（連續第 3 筆）的時間 */
    @Column(nullable = false)
    private LocalDateTime startedAt;

    @Column(nullable = false)
    private Double startLat;

    @Column(nullable = false)
    private Double startLng;

    /** 確定偏離時離那一段多遠，公尺 */
    @Column(nullable = false)
    private Double startDistanceMeters;

    /** 偏離 10 分鐘還沒結束、升級成警報的時間；沒升級是 null */
    private LocalDateTime escalatedAt;

    /** 結束時間；null＝進行中 */
    private LocalDateTime endedAt;

    /** 結束原因；進行中是 null。資料表是 VARCHAR(20)，存 enum 名稱 */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private RouteDeviationEndReason endReason;

    /**
     * 樂觀鎖。GPS 上傳的執行緒（回到路線→結束）跟每分鐘排程的執行緒（升級、休息→結束）可能同時改同一筆：
     * 沒有版本號的話，後存的那一方會用它手上的舊資料整列蓋回去（例如把 endedAt 蓋回 null）；
     * 有版本號則後存的那一方會失敗，下一筆 GPS 或下一分鐘的排程再重來一次。
     */
    @Version
    @Column(nullable = false)
    private Long version;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            // 明確指定台北時間：正式環境的容器預設是 UTC，LocalDateTime.now() 會差 8 小時
            createdAt = LocalDateTime.now(TAIPEI);
        }
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getRouteId() {
        return routeId;
    }

    public void setRouteId(Long routeId) {
        this.routeId = routeId;
    }

    public Long getDriverId() {
        return driverId;
    }

    public void setDriverId(Long driverId) {
        this.driverId = driverId;
    }

    public Integer getLegSequence() {
        return legSequence;
    }

    public void setLegSequence(Integer legSequence) {
        this.legSequence = legSequence;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(LocalDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public Double getStartLat() {
        return startLat;
    }

    public void setStartLat(Double startLat) {
        this.startLat = startLat;
    }

    public Double getStartLng() {
        return startLng;
    }

    public void setStartLng(Double startLng) {
        this.startLng = startLng;
    }

    public Double getStartDistanceMeters() {
        return startDistanceMeters;
    }

    public void setStartDistanceMeters(Double startDistanceMeters) {
        this.startDistanceMeters = startDistanceMeters;
    }

    public LocalDateTime getEscalatedAt() {
        return escalatedAt;
    }

    public void setEscalatedAt(LocalDateTime escalatedAt) {
        this.escalatedAt = escalatedAt;
    }

    public LocalDateTime getEndedAt() {
        return endedAt;
    }

    public void setEndedAt(LocalDateTime endedAt) {
        this.endedAt = endedAt;
    }

    public RouteDeviationEndReason getEndReason() {
        return endReason;
    }

    public void setEndReason(RouteDeviationEndReason endReason) {
        this.endReason = endReason;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
