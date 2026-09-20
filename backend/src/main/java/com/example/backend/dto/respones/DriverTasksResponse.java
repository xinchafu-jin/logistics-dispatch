package com.example.backend.dto.respones;

import com.example.backend.constants.OrderStatus;
import com.example.backend.constants.RouteStatus;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/** 登入司機當天已發布的配送任務。 */
public class DriverTasksResponse {

    private LocalDate date;
    private Long driverId;
    private String driverName;
    private List<RouteTask> routes;

    public DriverTasksResponse() {
    }

    public DriverTasksResponse(
            LocalDate date,
            Long driverId,
            String driverName,
            List<RouteTask> routes
    ) {
        this.date = date;
        this.driverId = driverId;
        this.driverName = driverName;
        this.routes = routes;
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

    public String getDriverName() {
        return driverName;
    }

    public void setDriverName(String driverName) {
        this.driverName = driverName;
    }

    public List<RouteTask> getRoutes() {
        return routes;
    }

    public void setRoutes(List<RouteTask> routes) {
        this.routes = routes;
    }

    public static class RouteTask {

        private Long routeId;
        private RouteStatus status;
        private Warehouse warehouse;
        private Vehicle vehicle;
        private Double totalDistance;
        private Double estimatedFuelCost;
        private Integer estimatedWorkMinutes;
        private Double loadRate;
        private Integer stopCount;
        private Integer totalBoxes;
        private List<Stop> stops;

        public RouteTask() {
        }

        public RouteTask(
                Long routeId,
                RouteStatus status,
                Warehouse warehouse,
                Vehicle vehicle,
                Double totalDistance,
                Double estimatedFuelCost,
                Integer estimatedWorkMinutes,
                Double loadRate,
                Integer stopCount,
                Integer totalBoxes,
                List<Stop> stops
        ) {
            this.routeId = routeId;
            this.status = status;
            this.warehouse = warehouse;
            this.vehicle = vehicle;
            this.totalDistance = totalDistance;
            this.estimatedFuelCost = estimatedFuelCost;
            this.estimatedWorkMinutes = estimatedWorkMinutes;
            this.loadRate = loadRate;
            this.stopCount = stopCount;
            this.totalBoxes = totalBoxes;
            this.stops = stops;
        }

        public Long getRouteId() {
            return routeId;
        }

        public void setRouteId(Long routeId) {
            this.routeId = routeId;
        }

        public RouteStatus getStatus() {
            return status;
        }

        public void setStatus(RouteStatus status) {
            this.status = status;
        }

        public Warehouse getWarehouse() {
            return warehouse;
        }

        public void setWarehouse(Warehouse warehouse) {
            this.warehouse = warehouse;
        }

        public Vehicle getVehicle() {
            return vehicle;
        }

        public void setVehicle(Vehicle vehicle) {
            this.vehicle = vehicle;
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

        public Integer getStopCount() {
            return stopCount;
        }

        public void setStopCount(Integer stopCount) {
            this.stopCount = stopCount;
        }

        public Integer getTotalBoxes() {
            return totalBoxes;
        }

        public void setTotalBoxes(Integer totalBoxes) {
            this.totalBoxes = totalBoxes;
        }

        public List<Stop> getStops() {
            return stops;
        }

        public void setStops(List<Stop> stops) {
            this.stops = stops;
        }
    }

    public static class Warehouse {

        private Long id;
        private String warehouseCode;
        private String name;
        private String address;
        private Double lat;
        private Double lng;
        private String phone;

        public Warehouse() {
        }

        public Warehouse(
                Long id,
                String warehouseCode,
                String name,
                String address,
                Double lat,
                Double lng,
                String phone
        ) {
            this.id = id;
            this.warehouseCode = warehouseCode;
            this.name = name;
            this.address = address;
            this.lat = lat;
            this.lng = lng;
            this.phone = phone;
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

        public String getPhone() {
            return phone;
        }

        public void setPhone(String phone) {
            this.phone = phone;
        }
    }

    public static class Vehicle {

        private Long id;
        private String plateNumber;
        private String vehicleType;
        private Integer capacity;

        public Vehicle() {
        }

        public Vehicle(Long id, String plateNumber, String vehicleType, Integer capacity) {
            this.id = id;
            this.plateNumber = plateNumber;
            this.vehicleType = vehicleType;
            this.capacity = capacity;
        }

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
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
    }

    public static class Stop {

        private Integer sequence;
        private Long orderId;
        private String orderNumber;
        private OrderStatus orderStatus;
        private Integer expectedBoxCount;
        private String itemDescription;
        private String orderNotes;
        private Long storeId;
        private String storeCode;
        private String storeName;
        private String address;
        private Double lat;
        private Double lng;
        private String contactName;
        private String phone;
        private LocalTime receivingStart;
        private LocalTime receivingEnd;

        public Stop() {
        }

        public Stop(
                Integer sequence,
                Long orderId,
                String orderNumber,
                OrderStatus orderStatus,
                Integer expectedBoxCount,
                String itemDescription,
                String orderNotes,
                Long storeId,
                String storeCode,
                String storeName,
                String address,
                Double lat,
                Double lng,
                String contactName,
                String phone,
                LocalTime receivingStart,
                LocalTime receivingEnd
        ) {
            this.sequence = sequence;
            this.orderId = orderId;
            this.orderNumber = orderNumber;
            this.orderStatus = orderStatus;
            this.expectedBoxCount = expectedBoxCount;
            this.itemDescription = itemDescription;
            this.orderNotes = orderNotes;
            this.storeId = storeId;
            this.storeCode = storeCode;
            this.storeName = storeName;
            this.address = address;
            this.lat = lat;
            this.lng = lng;
            this.contactName = contactName;
            this.phone = phone;
            this.receivingStart = receivingStart;
            this.receivingEnd = receivingEnd;
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

        public OrderStatus getOrderStatus() {
            return orderStatus;
        }

        public void setOrderStatus(OrderStatus orderStatus) {
            this.orderStatus = orderStatus;
        }

        public Integer getExpectedBoxCount() {
            return expectedBoxCount;
        }

        public void setExpectedBoxCount(Integer expectedBoxCount) {
            this.expectedBoxCount = expectedBoxCount;
        }

        public String getItemDescription() {
            return itemDescription;
        }

        public void setItemDescription(String itemDescription) {
            this.itemDescription = itemDescription;
        }

        public String getOrderNotes() {
            return orderNotes;
        }

        public void setOrderNotes(String orderNotes) {
            this.orderNotes = orderNotes;
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
}
