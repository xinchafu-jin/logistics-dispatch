package com.example.backend.dto.respones;

import java.util.List;

/**
 * 排車範本：dispatch_templates -> template_routes -> template_stops 三層結構。
 */
public class TemplatesDTO {

    private Long id;

    private String name;

    private String notes;

    private List<TemplateRouteResponse> routes;

    public TemplatesDTO() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public List<TemplateRouteResponse> getRoutes() {
        return routes;
    }

    public void setRoutes(List<TemplateRouteResponse> routes) {
        this.routes = routes;
    }

    /**
     * 範本裡的一條路線，對應 template_routes。
     */
    public static class TemplateRouteResponse {

        private Long id;

        private Long warehouseId;

        private Long vehicleId;

        private Long driverId;

        private List<TemplateStopResponse> stops;

        public TemplateRouteResponse() {
        }

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public Long getWarehouseId() {
            return warehouseId;
        }

        public void setWarehouseId(Long warehouseId) {
            this.warehouseId = warehouseId;
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

        public List<TemplateStopResponse> getStops() {
            return stops;
        }

        public void setStops(List<TemplateStopResponse> stops) {
            this.stops = stops;
        }
    }

    /**
     * 路線上的一個停靠點，對應 template_stops。
     */
    public static class TemplateStopResponse {

        private Long id;

        private Long storeId;

        /** 建議配送順序，從 1 開始 */
        private Integer sequence;

        public TemplateStopResponse() {
        }

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
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