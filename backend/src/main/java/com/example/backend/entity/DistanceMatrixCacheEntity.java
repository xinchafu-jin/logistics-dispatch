package com.example.backend.entity;

import com.example.backend.constants.LocationType;
import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 距離矩陣快取。倉庫與門市座標固定，故 OSRM 算過一次即存起來重複使用，
 * 只有新增或修改座標時才需要重算。
 *
 * 起訖點以「類型 + id」識別：倉庫與門市的 id 各自獨立，不共用編號空間。
 */
@Entity
@Table(name = "distance_matrix_cache", uniqueConstraints = {
        @UniqueConstraint(name = "uk_matrix_from_to",
                columnNames = {"fromType", "fromId", "toType", "toId"})
})
public class DistanceMatrixCacheEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LocationType fromType;

    @Column(nullable = false)
    private Long fromId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LocationType toType;

    @Column(nullable = false)
    private Long toId;

    /** 距離（公尺）*/
    @Column(nullable = false)
    private Integer distance;

    /** 行車時間（秒）*/
    @Column(nullable = false)
    private Integer duration;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    public void onSave() {
        this.updatedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public LocationType getFromType() {
        return fromType;
    }

    public void setFromType(LocationType fromType) {
        this.fromType = fromType;
    }

    public Long getFromId() {
        return fromId;
    }

    public void setFromId(Long fromId) {
        this.fromId = fromId;
    }

    public LocationType getToType() {
        return toType;
    }

    public void setToType(LocationType toType) {
        this.toType = toType;
    }

    public Long getToId() {
        return toId;
    }

    public void setToId(Long toId) {
        this.toId = toId;
    }

    public Integer getDistance() {
        return distance;
    }

    public void setDistance(Integer distance) {
        this.distance = distance;
    }

    public Integer getDuration() {
        return duration;
    }

    public void setDuration(Integer duration) {
        this.duration = duration;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
