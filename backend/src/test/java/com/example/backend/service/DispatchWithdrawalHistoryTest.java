package com.example.backend.service;

import com.example.backend.constants.OrderStatus;
import com.example.backend.constants.RouteStatus;
import com.example.backend.constants.VehicleStatus;
import com.example.backend.dao.*;
import com.example.backend.dispatch.OsrmClient;
import com.example.backend.dispatch.RouteOptimizer;
import com.example.backend.dto.request.ReassignDTO;
import com.example.backend.entity.*;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DispatchWithdrawalHistoryTest {
    @Test
    void 撤回後換待送司機不改已完成訂單的原司機和車輛() {
        LocalDate date = LocalDate.of(2026, 9, 27);
        OrdersDAO orders = mock(OrdersDAO.class);
        RoutesDAO routes = mock(RoutesDAO.class);
        VehiclesDAO vehicles = mock(VehiclesDAO.class);
        WarehousesDAO warehouses = mock(WarehousesDAO.class);
        StoresDAO stores = mock(StoresDAO.class);
        DriversDAO drivers = mock(DriversDAO.class);
        OsrmClient osrm = mock(OsrmClient.class);
        DispatchService dispatch = new DispatchService(orders, vehicles, warehouses, stores,
                routes, drivers, osrm, mock(RouteOptimizer.class),
                mock(OrderDispatchEligibilityService.class));

        RoutesEntity route = new RoutesEntity();
        route.setId(10L); route.setDate(date); route.setWarehouseId(1L);
        route.setVehicleId(110L); route.setDriverId(5L); route.setStatus(RouteStatus.PUBLISHED);
        OrdersEntity completed = order(1L, OrderStatus.COMPLETED, date);
        OrdersEntity pending = order(2L, OrderStatus.CONFIRMED, date);
        WarehousesEntity warehouse = new WarehousesEntity();
        warehouse.setId(1L); warehouse.setLat(22.6); warehouse.setLng(120.3);
        VehiclesEntity vehicle = new VehiclesEntity();
        vehicle.setId(110L); vehicle.setWarehouseId(1L); vehicle.setStatus(VehicleStatus.AVAILABLE);
        vehicle.setCapacity(100); vehicle.setPlateNumber("CAR-TEST");
        DriversEntity replacement = new DriversEntity();
        replacement.setId(6L); replacement.setWarehouseId(1L); replacement.setIsActive(true);
        StoresEntity store = new StoresEntity();
        store.setId(42L); store.setLat(22.7); store.setLng(120.4);

        when(routes.findByDate(date)).thenReturn(List.of(route));
        when(routes.findByDateAndWarehouseId(date, 1L)).thenReturn(List.of(route));
        when(routes.save(any())).thenAnswer(call -> call.getArgument(0));
        when(orders.findByRouteIdIn(List.of(10L))).thenReturn(List.of(completed, pending));
        when(orders.findByDeliveryDateAndWarehouseId(date, 1L)).thenReturn(List.of(completed, pending));
        when(orders.findByRouteIdAndStatusInOrderBySequence(eq(10L), any())).thenReturn(List.of(pending));
        when(warehouses.findById(1L)).thenReturn(Optional.of(warehouse));
        when(vehicles.findById(110L)).thenReturn(Optional.of(vehicle));
        when(vehicles.findAllById(any())).thenReturn(List.of(vehicle));
        when(drivers.findAllById(any())).thenReturn(List.of(replacement));
        when(stores.findAllById(any())).thenReturn(List.of(store));
        when(osrm.table(any())).thenReturn(new long[][]{{0, 100}, {100, 0}});

        DispatchGuardService guard = new DispatchGuardService(mock(DriverShiftsDAO.class), drivers,
                routes, orders, vehicles, mock(ScheduleMonthsDAO.class));
        assertDoesNotThrow(() -> guard.assertCanWithdraw(date));
        dispatch.withdraw(date);
        assertEquals(RouteStatus.DRAFT, route.getStatus());

        ReassignDTO request = new ReassignDTO();
        request.setDate(date); request.setWarehouseId(1L);
        ReassignDTO.RouteAssignment assignment = new ReassignDTO.RouteAssignment();
        assignment.setVehicleId(110L); assignment.setDriverId(6L); assignment.setOrderIds(List.of(2L));
        request.setRoutes(List.of(assignment));
        dispatch.reassign(request);

        assertEquals(6L, pending.getAssignedDriverId());
        assertEquals(OrderStatus.CONFIRMED, pending.getStatus());
        assertEquals(5L, completed.getAssignedDriverId());
        assertEquals(110L, completed.getAssignedVehicleId());
        assertEquals(10L, completed.getRouteId());
        assertEquals(OrderStatus.COMPLETED, completed.getStatus());
        verify(routes, never()).deleteAllById(any());
    }

    private OrdersEntity order(Long id, OrderStatus status, LocalDate date) {
        OrdersEntity order = new OrdersEntity();
        order.setId(id); order.setOrderNumber("DO-TEST-" + id); order.setStatus(status);
        order.setDeliveryDate(date); order.setWarehouseId(1L); order.setStoreId(42L);
        order.setRouteId(10L); order.setSequence(id.intValue()); order.setBoxCount(10);
        order.setAssignedDriverId(5L); order.setAssignedVehicleId(110L);
        return order;
    }
}
