package com.example.backend.service;

import com.example.backend.constants.OrderStatus;
import com.example.backend.dao.*;
import com.example.backend.dispatch.*;
import com.example.backend.entity.*;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.AdditionalMatchers.aryEq;

class MaintenanceRouteProjectionTest {
    @Test void plannedDistanceIncludesReturnToWarehouse() {
        var routes = mock(RoutesDAO.class); var orders = mock(OrdersDAO.class);
        var warehouses = mock(WarehousesDAO.class); var stores = mock(StoresDAO.class);
        var vehicles = mock(VehiclesDAO.class); var osrm = mock(OsrmClient.class);
        var route = new RoutesEntity(); route.setId(1L); route.setWarehouseId(1L); route.setVehicleId(1L); route.setDate(LocalDate.of(2026, 9, 26));
        var warehouse = new WarehousesEntity(); warehouse.setName("倉庫"); warehouse.setLat(22.6); warehouse.setLng(120.3);
        var store = new StoresEntity(); store.setId(2L); store.setName("門市"); store.setLat(22.7); store.setLng(120.4);
        var order = new OrdersEntity(); order.setStoreId(2L); order.setStatus(OrderStatus.CONFIRMED);
        when(orders.findByRouteIdOrderBySequence(1L)).thenReturn(List.of(order));
        when(warehouses.findById(1L)).thenReturn(Optional.of(warehouse));
        when(stores.findAllById(any())).thenReturn(List.of(store));
        when(vehicles.findById(1L)).thenReturn(Optional.of(new VehiclesEntity()));
        var outward = new OsrmRouteResponse.Route(); outward.setDistance(12000); outward.setDuration(900);
        var returning = new OsrmRouteResponse.Route(); returning.setDistance(18000); returning.setDuration(1200);
        when(osrm.route(any(), any())).thenReturn(outward, returning);
        var service = new RoutePlanMetricsService(routes, orders, warehouses, stores, vehicles, mock(MileageLogsDAO.class), osrm,
                mock(FuelPriceService.class), mock(GpsDistanceService.class), mock(GpsPingsDAO.class), mock(VehicleMaintenanceService.class), 10);
        assertEquals(30.0, service.plannedKm(route));
        verify(osrm).route(aryEq(new double[]{120.3, 22.6}), aryEq(new double[]{120.4, 22.7}));
        verify(osrm).route(aryEq(new double[]{120.4, 22.7}), aryEq(new double[]{120.3, 22.6}));
    }

    @Test void draftMetricsIncludeMaintenanceBeforeAnyTripAndDoNotWriteMileage() {
        var routes = mock(RoutesDAO.class); var orders = mock(OrdersDAO.class);
        var warehouses = mock(WarehousesDAO.class); var stores = mock(StoresDAO.class);
        var vehicles = mock(VehiclesDAO.class); var osrm = mock(OsrmClient.class);
        var maintenance = mock(VehicleMaintenanceService.class);
        var route = new RoutesEntity(); route.setId(1L); route.setWarehouseId(1L); route.setVehicleId(1L);
        route.setDate(LocalDate.of(2026, 9, 26));
        var warehouse = new WarehousesEntity(); warehouse.setName("倉庫"); warehouse.setLat(22.6); warehouse.setLng(120.3);
        var store = new StoresEntity(); store.setId(2L); store.setName("門市"); store.setLat(22.7); store.setLng(120.4);
        var order = new OrdersEntity(); order.setStoreId(2L); order.setStatus(OrderStatus.CONFIRMED);
        var completed = new OrdersEntity(); completed.setStoreId(3L); completed.setStatus(OrderStatus.COMPLETED);
        var vehicle = new VehiclesEntity(); vehicle.setId(1L); vehicle.setCurrentOdometerKm(100);
        var policy = new VehicleMaintenancePolicy(); policy.minorIntervalKm = 3000; policy.majorIntervalKm = 20000; policy.retirementKm = 500000;
        vehicle.setLastMinorMaintenanceKm(0); vehicle.setLastMajorMaintenanceKm(0);
        var summary = VehicleMaintenanceService.assess(vehicle, policy, 500, 30.0, 0, 0, 0, null, null, null);
        when(routes.findById(1L)).thenReturn(Optional.of(route));
        when(orders.findByRouteIdOrderBySequence(1L)).thenReturn(List.of(order, completed));
        when(warehouses.findById(1L)).thenReturn(Optional.of(warehouse)); when(stores.findAllById(any())).thenReturn(List.of(store));
        when(vehicles.findById(1L)).thenReturn(Optional.of(vehicle)); when(maintenance.summary(vehicle, 30.0)).thenReturn(summary);
        var outward = new OsrmRouteResponse.Route(); outward.setDistance(12000); outward.setDuration(900);
        var returning = new OsrmRouteResponse.Route(); returning.setDistance(18000); returning.setDuration(1200);
        when(osrm.route(any(), any())).thenReturn(outward, returning);
        var service = new RoutePlanMetricsService(routes, orders, warehouses, stores, vehicles, mock(MileageLogsDAO.class), osrm,
                mock(FuelPriceService.class), mock(GpsDistanceService.class), mock(GpsPingsDAO.class), maintenance, 10);
        var result = service.getMetrics(1L);
        assertEquals("NO_TRIP_BOUNDARY", result.getMileageStatus());
        assertEquals(summary, result.getMaintenance()); assertEquals(30.0, result.getMaintenance().plannedKm());
        assertEquals(2870.0, result.getMaintenance().projectedMinorKm());
        assertEquals(100, vehicle.getCurrentOdometerKm());
        verify(stores).findAllById(Set.of(2L)); verify(vehicles, never()).save(any()); verify(routes, never()).save(any());
    }
}
