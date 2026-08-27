package com.example.backend.dto.respones;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * 排車結果。對應 POST /api/dispatch/optimize 的回應。
 *
 * 除了 id 之外一併帶出前端畫面需要的名稱與座標，避免前端還要逐筆回打
 * /api/stores、/api/vehicles 才能顯示。
 */
public class DispatchResponse {

    /** 配送日期 */
    private LocalDate date;

    /** 出發倉庫，地圖上的起訖點 */
    private WarehouseResponse warehouse;

    /** 每台有出車的車輛各一筆 */
    private List<RouteResponse> routes;

    /** 車輛裝不下、沒排進去的訂單，狀態維持 CONFIRMED，下次排程還會被撈到 */
    private List<UnassignedOrderResponse> unassignedOrders;

    public DispatchResponse() {
    }

    public LocalDate getDate() {
        return date;
    }

    public void setDate(LocalDate date) {
        this.date = date;
    }

    public WarehouseResponse getWarehouse() {
        return warehouse;
    }

    public void setWarehouse(WarehouseResponse warehouse) {
        this.warehouse = warehouse;
    }

    public List<RouteResponse> getRoutes() {
        return routes;
    }

    public void setRoutes(List<RouteResponse> routes) {
        this.routes = routes;
    }

    public List<UnassignedOrderResponse> getUnassignedOrders() {
        return unassignedOrders;
    }

    public void setUnassignedOrders(List<UnassignedOrderResponse> unassignedOrders) {
        this.unassignedOrders = unassignedOrders;
    }

    /**
     * 倉庫，路線的起點與終點。
     */
    public static class WarehouseResponse {

        private Long id;

        private String warehouseCode;

        private String name;

        private String address;

        private Double lat;

        private Double lng;

        public WarehouseResponse() {
        }

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getWarehouseCode() {
            return warehouseCode;
        }

        public void setWarehouseCode(String warehouseCode) {
            this.warehouseCode = warehouseCode;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getAddress() {
            return address;
        }

        public void setAddress(String address) {
            this.address = address;
        }

        public Double getLat() {
            return lat;
        }

        public void setLat(Double lat) {
            this.lat = lat;
        }

        public Double getLng() {
            return lng;
        }

        public void setLng(Double lng) {
            this.lng = lng;
        }
    }

    /**
     * 一台車某天的一條路線。
     */
    public static class RouteResponse {

        /** routes 表的主鍵，前端要指派司機、調整順序時用得到 */
        private Long routeId;

        private Long vehicleId;

        /** 車牌，畫面上顯示這個而不是 vehicleId */
        private String plateNumber;

        private String vehicleType;

        /** 車輛容量（箱） */
        private Integer capacity;

        /** 草稿階段為 null，發布前才指派 */
        private Long driverId;

        /** 司機姓名，未指派時為 null */
        private String driverName;

        private List<StopResponse> stops;

        /** 停靠點數量，等於 stops.size() */
        private Integer stopCount;

        /** 實際載運箱數 */
        private Integer loadedBoxes;

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

        public Long getRouteId() {
            return routeId;
        }

        public void setRouteId(Long routeId) {
            this.routeId = routeId;
        }

        public Long getVehicleId() {
            return vehicleId;
        }

        public void setVehicleId(Long vehicleId) {
            this.vehicleId = vehicleId;
        }

        public String getPlateNumber() {
            return plateNumber;
        }

        public void setPlateNumber(String plateNumber) {
            this.plateNumber = plateNumber;
        }

        public String getVehicleType() {
            return vehicleType;
        }

        public void setVehicleType(String vehicleType) {
            this.vehicleType = vehicleType;
        }

        public Integer getCapacity() {
            return capacity;
        }

        public void setCapacity(Integer capacity) {
            this.capacity = capacity;
        }

        public Long getDriverId() {
            return driverId;
        }

        public void setDriverId(Long driverId) {
            this.driverId = driverId;
        }

        public String getDriverName() {
            return driverName;
        }

        public void setDriverName(String driverName) {
            this.driverName = driverName;
        }

        public List<StopResponse> getStops() {
            return stops;
        }

        public void setStops(List<StopResponse> stops) {
            this.stops = stops;
        }

        public Integer getStopCount() {
            return stopCount;
        }

        public void setStopCount(Integer stopCount) {
            this.stopCount = stopCount;
        }

        public Integer getLoadedBoxes() {
            return loadedBoxes;
        }

        public void setLoadedBoxes(Integer loadedBoxes) {
            this.loadedBoxes = loadedBoxes;
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

        /** 建議配送順序，從 1 開始 */
        private Integer sequence;

        private Long orderId;

        private String orderNumber;

        /** 箱數 */
        private Integer boxCount;

        private String itemDescription;

        private Long storeId;

        private String storeCode;

        private String storeName;

        private String address;

        private Double lat;

        private Double lng;

        private String contactName;

        private String phone;

        /** 可收貨時間起，前端顯示時段用 */
        private LocalTime receivingStart;

        /** 可收貨時間迄 */
        private LocalTime receivingEnd;

        public StopResponse() {
        }

        public Integer getSequence() {
            return sequence;
        }

        public void setSequence(Integer sequence) {
            this.sequence = sequence;
        }

        public Long getOrderId() {
            return orderId;
        }

        public void setOrderId(Long orderId) {
            this.orderId = orderId;
        }

        public String getOrderNumber() {
            return orderNumber;
        }

        public void setOrderNumber(String orderNumber) {
            this.orderNumber = orderNumber;
        }

        public Integer getBoxCount() {
            return boxCount;
        }

        public void setBoxCount(Integer boxCount) {
            this.boxCount = boxCount;
        }

        public String getItemDescription() {
            return itemDescription;
        }

        public void setItemDescription(String itemDescription) {
            this.itemDescription = itemDescription;
        }

        public Long getStoreId() {
            return storeId;
        }

        public void setStoreId(Long storeId) {
            this.storeId = storeId;
        }

        public String getStoreCode() {
            return storeCode;
        }

        public void setStoreCode(String storeCode) {
            this.storeCode = storeCode;
        }

        public String getStoreName() {
            return storeName;
        }

        public void setStoreName(String storeName) {
            this.storeName = storeName;
        }

        public String getAddress() {
            return address;
        }

        public void setAddress(String address) {
            this.address = address;
        }

        public Double getLat() {
            return lat;
        }

        public void setLat(Double lat) {
            this.lat = lat;
        }

        public Double getLng() {
            return lng;
        }

        public void setLng(Double lng) {
            this.lng = lng;
        }

        public String getContactName() {
            return contactName;
        }

        public void setContactName(String contactName) {
            this.contactName = contactName;
        }

        public String getPhone() {
            return phone;
        }

        public void setPhone(String phone) {
            this.phone = phone;
        }

        public LocalTime getReceivingStart() {
            return receivingStart;
        }

        public void setReceivingStart(LocalTime receivingStart) {
            this.receivingStart = receivingStart;
        }

        public LocalTime getReceivingEnd() {
            return receivingEnd;
        }

        public void setReceivingEnd(LocalTime receivingEnd) {
            this.receivingEnd = receivingEnd;
        }
    }

    /**
     * 沒排進任何路線的訂單，前端要在側邊欄列出來讓調度員處理。
     */
    public static class UnassignedOrderResponse {

        private Long orderId;

        private String orderNumber;

        private Integer boxCount;

        private Long storeId;

        private String storeCode;

        private String storeName;

        private String address;

        private Double lat;

        private Double lng;

        public UnassignedOrderResponse() {
        }

        public Long getOrderId() {
            return orderId;
        }

        public void setOrderId(Long orderId) {
            this.orderId = orderId;
        }

        public String getOrderNumber() {
            return orderNumber;
        }

        public void setOrderNumber(String orderNumber) {
            this.orderNumber = orderNumber;
        }

        public Integer getBoxCount() {
            return boxCount;
        }

        public void setBoxCount(Integer boxCount) {
            this.boxCount = boxCount;
        }

        public Long getStoreId() {
            return storeId;
        }

        public void setStoreId(Long storeId) {
            this.storeId = storeId;
        }

        public String getStoreCode() {
            return storeCode;
        }

        public void setStoreCode(String storeCode) {
            this.storeCode = storeCode;
        }

        public String getStoreName() {
            return storeName;
        }

        public void setStoreName(String storeName) {
            this.storeName = storeName;
        }

        public String getAddress() {
            return address;
        }

        public void setAddress(String address) {
            this.address = address;
        }

        public Double getLat() {
            return lat;
        }

        public void setLat(Double lat) {
            this.lat = lat;
        }

        public Double getLng() {
            return lng;
        }

        public void setLng(Double lng) {
            this.lng = lng;
        }
    }
}
