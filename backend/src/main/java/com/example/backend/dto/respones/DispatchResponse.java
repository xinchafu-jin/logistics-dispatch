package com.example.backend.dto.respones;

import java.util.List;

/**
 * 排車結果。對應 POST /api/dispatch/optimize 的回應。
 */
public class DispatchResponse {

    /** 每台有出車的車輛各一筆 */
    private List<RouteResponse> routes;

    /** 車輛裝不下、沒排進去的訂單，狀態維持 CONFIRMED，下次排程還會被撈到 */
    private List<Long> unassignedOrderIds;

    public DispatchResponse() {
    }

    public List<RouteResponse> getRoutes() {
        return routes;
    }

    public void setRoutes(List<RouteResponse> routes) {
        this.routes = routes;
    }

    public List<Long> getUnassignedOrderIds() {
        return unassignedOrderIds;
    }

    public void setUnassignedOrderIds(List<Long> unassignedOrderIds) {
        this.unassignedOrderIds = unassignedOrderIds;
    }

    /**
     * 一台車某天的一條路線。
     */
    public static class RouteResponse {

        private Long vehicleId;

        /** 草稿階段為 null，發布前才指派 */
        private Long driverId;

        private List<StopResponse> stops;

        /** 總里程（公尺） */
        private Double totalDistance;

        /** 預估油耗成本，目前未計算（系統尚無油價設定） */
        private Double estimatedFuelCost;

        /** 預估總工時（分鐘），目前未計算（需 OSRM durations） */
        private Integer estimatedWorkMinutes;

        /** 裝載率 0~1，實際載運箱數 ÷ 車輛容量 */
        private Double loadRate;

        public RouteResponse() {
        }

        public Long getVehicleId() {
            return vehicleId;
        }

        public void setVehicleId(Long vehicleId) {
            this.vehicleId = vehicleId;
        }

        public Long getDriverId() {
            return driverId;
        }

        public void setDriverId(Long driverId) {
            this.driverId = driverId;
        }

        public List<StopResponse> getStops() {
            return stops;
        }

        public void setStops(List<StopResponse> stops) {
            this.stops = stops;
        }

        public Double getTotalDistance() {
            return totalDistance;
        }

        public void setTotalDistance(Double totalDistance) {
            this.totalDistance = totalDistance;
        }

        public Double getEstimatedFuelCost() {
            return estimatedFuelCost;
        }

        public void setEstimatedFuelCost(Double estimatedFuelCost) {
            this.estimatedFuelCost = estimatedFuelCost;
        }

        public Integer getEstimatedWorkMinutes() {
            return estimatedWorkMinutes;
        }

        public void setEstimatedWorkMinutes(Integer estimatedWorkMinutes) {
            this.estimatedWorkMinutes = estimatedWorkMinutes;
        }

        public Double getLoadRate() {
            return loadRate;
        }

        public void setLoadRate(Double loadRate) {
            this.loadRate = loadRate;
        }
    }

    /**
     * 路線上的一個停靠點，等於一張訂單。
     */
    public static class StopResponse {

        private Long orderId;

        private Long storeId;

        /** 建議配送順序，從 1 開始 */
        private Integer sequence;

        public StopResponse() {
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

        public Integer getSequence() {
            return sequence;
        }

        public void setSequence(Integer sequence) {
            this.sequence = sequence;
        }
    }
}
