package com.example.backend.dto.respones;

import java.util.ArrayList;
import java.util.List;

/**
 * 一條已發布路線的預定道路形狀，後台地圖沿實際道路畫線用。
 *
 * <p>分段回傳而不是整條接成一條線：之後偏離警報要標出司機偏離的是哪一段，前端要認得出段號。</p>
 */
public class PlannedPathResponse {

    private Long routeId;
    /** 依行駛順序：倉庫 → 各門市 → 回倉 */
    private List<Leg> legs = new ArrayList<>();

    public PlannedPathResponse() {
    }

    public PlannedPathResponse(Long routeId) {
        this.routeId = routeId;
    }

    public Long getRouteId() {
        return routeId;
    }

    public void setRouteId(Long routeId) {
        this.routeId = routeId;
    }

    public List<Leg> getLegs() {
        return legs;
    }

    public void setLegs(List<Leg> legs) {
        this.legs = legs;
    }

    public static class Leg {
        /** 第幾段，從 1 開始；跟 route_planned_legs.sequence 一致 */
        private int sequence;
        /** 這一段要開到的門市；null＝回倉那一段 */
        private Long toStoreId;
        /** [[經度, 緯度], ...]：GeoJSON／MapLibre 的順序，前端直接當 LineString 的 coordinates */
        private double[][] path;

        public Leg() {
        }

        public int getSequence() {
            return sequence;
        }

        public void setSequence(int sequence) {
            this.sequence = sequence;
        }

        public Long getToStoreId() {
            return toStoreId;
        }

        public void setToStoreId(Long toStoreId) {
            this.toStoreId = toStoreId;
        }

        public double[][] getPath() {
            return path;
        }

        public void setPath(double[][] path) {
            this.path = path;
        }
    }
}
