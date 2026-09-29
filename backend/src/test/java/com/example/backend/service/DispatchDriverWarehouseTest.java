package com.example.backend.service;

import com.example.backend.constants.VehicleStatus;
import com.example.backend.constants.OrderStatus;
import com.example.backend.dao.*;
import com.example.backend.dispatch.*;
import com.example.backend.dto.request.ReassignDTO;
import com.example.backend.entity.*;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DispatchDriverWarehouseTest {
    @Test void allowsDriversFromAnyWarehouseOrWithoutAffiliation() {
        OrdersDAO orders = mock(OrdersDAO.class);
        VehiclesDAO vehicles = mock(VehiclesDAO.class);
        WarehousesDAO warehouses = mock(WarehousesDAO.class);
        RoutesDAO routes = mock(RoutesDAO.class);
        DriversDAO drivers = mock(DriversDAO.class);
        var warehouse = new WarehousesEntity(); warehouse.setId(1L); warehouse.setLat(22.9); warehouse.setLng(120.2);
        when(warehouses.findById(1L)).thenReturn(Optional.of(warehouse));
        var vehicle = new VehiclesEntity(); vehicle.setId(1L); vehicle.setWarehouseId(1L);
        vehicle.setStatus(VehicleStatus.AVAILABLE); vehicle.setCapacity(10);
        when(vehicles.findAllById(any())).thenReturn(List.of(vehicle));
        var order = new OrdersEntity(); order.setId(101L); order.setStoreId(11L);
        order.setOrderNumber("DO-101"); order.setStatus(OrderStatus.CONFIRMED); order.setBoxCount(3);
        when(orders.findByDeliveryDateAndWarehouseId(any(), any())).thenReturn(List.of(order));
        StoresDAO stores = mock(StoresDAO.class);
        var store = new StoresEntity(); store.setId(11L); store.setLat(22.8); store.setLng(120.3);
        when(stores.findAllById(any())).thenReturn(List.of(store));
        var driver = new DriversEntity(); driver.setId(1L); driver.setName("測試司機"); driver.setIsActive(true);
        when(drivers.findAllById(any())).thenReturn(List.of(driver));
        var osrm = mock(OsrmClient.class);
        when(osrm.table(any())).thenReturn(new long[][]{{0L, 100L}, {100L, 0L}});
        when(routes.save(any(RoutesEntity.class))).thenAnswer(invocation -> {
            RoutesEntity route = invocation.getArgument(0);
            route.setId(10L);
            return route;
        });
        var dto = new ReassignDTO(); dto.setDate(LocalDate.of(2026, 10, 1)); dto.setWarehouseId(1L);
        var assignment = new ReassignDTO.RouteAssignment(); assignment.setVehicleId(1L); assignment.setDriverId(1L); assignment.setOrderIds(List.of(101L));
        dto.setRoutes(List.of(assignment));
        var service = new DispatchService(orders, vehicles, warehouses, stores, routes, drivers,
                osrm, mock(RouteOptimizer.class));
        for (Long id : new Long[]{null, 2L}) {
            driver.setWarehouseId(id);
            assertDoesNotThrow(() -> service.reassign(dto));
            assertEquals(1L, order.getAssignedDriverId());
            assertEquals(10L, order.getRouteId());
        }
        verify(routes, times(2)).save(any(RoutesEntity.class));
    }

    @Test void stillRejectsDriverAlreadyAssignedAtAnotherWarehouseThatDay() {
        OrdersDAO orders = mock(OrdersDAO.class);
        VehiclesDAO vehicles = mock(VehiclesDAO.class);
        WarehousesDAO warehouses = mock(WarehousesDAO.class);
        RoutesDAO routes = mock(RoutesDAO.class);
        DriversDAO drivers = mock(DriversDAO.class);
        var warehouse = new WarehousesEntity(); warehouse.setId(1L);
        when(warehouses.findById(1L)).thenReturn(Optional.of(warehouse));
        var vehicle = new VehiclesEntity(); vehicle.setId(1L); vehicle.setWarehouseId(1L); vehicle.setStatus(VehicleStatus.AVAILABLE);
        when(vehicles.findAllById(any())).thenReturn(List.of(vehicle));
        var driver = new DriversEntity(); driver.setId(1L); driver.setWarehouseId(2L);
        driver.setName("測試司機"); driver.setIsActive(true);
        when(drivers.findAllById(any())).thenReturn(List.of(driver));
        when(drivers.findById(1L)).thenReturn(Optional.of(driver));
        var otherRoute = new RoutesEntity(); otherRoute.setId(20L); otherRoute.setWarehouseId(2L);
        otherRoute.setDriverId(1L); otherRoute.setVehicleId(99L);
        when(routes.findByDateAndDriverIdIsNotNull(any())).thenReturn(List.of(otherRoute));
        var activeOrder = new OrdersEntity(); activeOrder.setStatus(OrderStatus.CONFIRMED);
        when(orders.findByRouteIdAndStatusInOrderBySequence(any(), any())).thenReturn(List.of(activeOrder));
        var dto = new ReassignDTO(); dto.setDate(LocalDate.of(2026, 10, 1)); dto.setWarehouseId(1L);
        var assignment = new ReassignDTO.RouteAssignment(); assignment.setVehicleId(1L); assignment.setDriverId(1L); assignment.setOrderIds(List.of());
        dto.setRoutes(List.of(assignment));
        var service = new DispatchService(orders, vehicles, warehouses, mock(StoresDAO.class), routes, drivers,
                mock(OsrmClient.class), mock(RouteOptimizer.class));

        assertTrue(assertThrows(IllegalArgumentException.class, () -> service.reassign(dto))
                .getMessage().contains("無法重複指派"));
        verify(routes, never()).save(any(RoutesEntity.class));
    }
}
