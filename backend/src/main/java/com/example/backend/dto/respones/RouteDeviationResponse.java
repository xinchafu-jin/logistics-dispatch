package com.example.backend.dto.respones;

import com.example.backend.constants.RouteDeviationEndReason;
import com.example.backend.entity.RouteDeviationsEntity;

import java.time.LocalDateTime;

/**
 * 一筆偏離紀錄給後台看的樣子：進行中清單（GET /api/fleet/route-deviations）與推播共用。
 *
 * <p>escalatedAt 有值是「警報」、沒有是「提示」；endedAt 有值代表已經結束。
 * 已偏離幾分鐘由前端用 startedAt 算，後端不必每分鐘推一次。</p>
 */
public class RouteDeviationResponse {

    private Long id;
    private Long routeId;
    private Long driverId;
    private Integer legSequence;
    private LocalDateTime startedAt;
    private Double startLat;
    private Double startLng;
    private Double startDistanceMeters;
    private LocalDateTime escalatedAt;
    private LocalDateTime endedAt;
    private RouteDeviationEndReason endReason;

    public RouteDeviationResponse() {
    }

    public static RouteDeviationResponse from(RouteDeviationsEntity entity) {
        RouteDeviationResponse response = new RouteDeviationResponse();
        response.setId(entity.getId());
        response.setRouteId(entity.getRouteId());
        response.setDriverId(entity.getDriverId());
        response.setLegSequence(entity.getLegSequence());
        response.setStartedAt(entity.getStartedAt());
        response.setStartLat(entity.getStartLat());
        response.setStartLng(entity.getStartLng());
        response.setStartDistanceMeters(entity.getStartDistanceMeters());
        response.setEscalatedAt(entity.getEscalatedAt());
        response.setEndedAt(entity.getEndedAt());
        response.setEndReason(entity.getEndReason());
        return response;
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
}
