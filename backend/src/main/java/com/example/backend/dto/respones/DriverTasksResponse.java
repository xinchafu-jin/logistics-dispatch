package com.example.backend.dto.respones;

import com.example.backend.constants.OrderStatus;
import com.example.backend.constants.RouteStatus;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/** 登入司機當天已發布的配送任務。 */
public record DriverTasksResponse(
        LocalDate date,
        Long driverId,
        String driverName,
        List<RouteTask> routes
) {

    public record RouteTask(
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
    }

    public record Warehouse(
            Long id,
            String warehouseCode,
            String name,
            String address,
            Double lat,
            Double lng,
            String phone
    ) {
    }

    public record Vehicle(
            Long id,
            String plateNumber,
            String vehicleType,
            Integer capacity
    ) {
    }

    public record Stop(
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
    }
}
