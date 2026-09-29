package com.example.backend.entity;

import com.example.backend.constants.RouteLegLocationType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 發布時存下的一段預定道路形狀：從倉庫或上一站到下一站。
 *
 * <p>後台畫已發布路線、偏離預定路線判斷都用這份，兩邊看到的是同一條路。
 * 切段方式與欄位命名跟 {@link RouteLegMileagesEntity}（實際里程）一致，可用 routeId + sequence 對照。</p>
 */
@Entity
@Table(name = "route_planned_legs")
public class RoutePlannedLegsEntity {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long routeId;

    /** 第幾段，從 1 開始；跟 RoutePlanMetricsService 算出來的 leg.sequence 一致 */
    @Column(nullable = false)
    private Integer sequence;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RouteLegLocationType fromType;

    /** 起點是倉庫時為 null */
    private Long fromStoreId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RouteLegLocationType toType;

    /** 終點是倉庫（回倉那一段）時為 null */
    private Long toStoreId;

    /** OSRM 算的道路距離，公尺 */
    @Column(nullable = false)
    private Double distanceMeters;

    /** OSRM 估的車程，秒 */
    @Column(nullable = false)
    private Double durationSeconds;

    /**
     * 道路形狀，JSON 陣列 [[經度, 緯度], ...]（OSRM geometries=geojson 的 coordinates 原樣存）。
     * 這裡只存字串，序列化與解析交給使用的 Service。
     * 資料表是 MEDIUMTEXT；ddl-auto=none，columnDefinition 只是讓讀程式的人知道型別。
     */
    @Column(nullable = false, columnDefinition = "MEDIUMTEXT")
    private String path;

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

    public Integer getSequence() {
        return sequence;
    }

    public void setSequence(Integer sequence) {
        this.sequence = sequence;
    }

    public RouteLegLocationType getFromType() {
        return fromType;
    }

    public void setFromType(RouteLegLocationType fromType) {
        this.fromType = fromType;
    }

    public Long getFromStoreId() {
        return fromStoreId;
    }

    public void setFromStoreId(Long fromStoreId) {
        this.fromStoreId = fromStoreId;
    }

    public RouteLegLocationType getToType() {
        return toType;
    }

    public void setToType(RouteLegLocationType toType) {
        this.toType = toType;
    }

    public Long getToStoreId() {
        return toStoreId;
    }

    public void setToStoreId(Long toStoreId) {
        this.toStoreId = toStoreId;
    }

    public Double getDistanceMeters() {
        return distanceMeters;
    }

    public void setDistanceMeters(Double distanceMeters) {
        this.distanceMeters = distanceMeters;
    }

    public Double getDurationSeconds() {
        return durationSeconds;
    }

    public void setDurationSeconds(Double durationSeconds) {
        this.durationSeconds = durationSeconds;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
