package com.example.backend.service;

import com.example.backend.constants.OrderStatus;
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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.*;

class DispatchDriverWarehouseTest {

    @Test
    void driverCanDepartFromWarehouseOtherThanHomeWarehouseOnAnotherDate() {
        TestContext context = context();
        context.driver.setWarehouseId(2L);
        when(context.routes.save(any(RoutesEntity.class))).thenAnswer(invocation -> {
            RoutesEntity route = invocation.getArgument(0);
            route.setId(30L);
            return route;
        });
        when(context.osrm.table(any())).thenReturn(new long[][]{{0L}});

        assertDoesNotThrow(() -> context.service.reassign(context.dto));

        verify(context.routes).save(any(RoutesEntity.class));
    }

    @Test
    void sameDriverCannotDepartFromTwoWarehousesOnTheSameDate() {
        TestContext context = context();
        context.driver.setWarehouseId(2L);

        RoutesEntity otherWarehouseRoute = new RoutesEntity();
        otherWarehouseRoute.setId(99L);
        otherWarehouseRoute.setDate(context.dto.getDate());
        otherWarehouseRoute.setWarehouseId(2L);
        otherWarehouseRoute.setVehicleId(9L);
        otherWarehouseRoute.setDriverId(1L);
        when(context.routes.findByDateAndDriverIdIsNotNull(context.dto.getDate()))
                .thenReturn(List.of(otherWarehouseRoute));

        OrdersEntity activeOrder = new OrdersEntity();
        activeOrder.setStatus(OrderStatus.CONFIRMED);
        when(context.orders.findByRouteIdAndStatusInOrderBySequence(eq(99L), anySet()))
                .thenReturn(List.of(activeOrder));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> context.service.reassign(context.dto));

        assertTrue(error.getMessage().contains("當天已排"), error.getMessage());
        verify(context.routes, never()).save(any());
    }

    private TestContext context() {
        OrdersDAO orders = mock(OrdersDAO.class);
        VehiclesDAO vehicles = mock(VehiclesDAO.class);
        WarehousesDAO warehouses = mock(WarehousesDAO.class);
        RoutesDAO routes = mock(RoutesDAO.class);
        DriversDAO drivers = mock(DriversDAO.class);
        OsrmClient osrm = mock(OsrmClient.class);

        WarehousesEntity warehouse = new WarehousesEntity();
        warehouse.setId(1L);
        warehouse.setName("北區倉");
        warehouse.setLat(25.0);
        warehouse.setLng(121.0);
        when(warehouses.findById(1L)).thenReturn(Optional.of(warehouse));

        VehiclesEntity vehicle = new VehiclesEntity();
        vehicle.setId(1L);
        vehicle.setWarehouseId(1L);
        vehicle.setPlateNumber("ABC-1234");
        vehicle.setCapacity(100);
        vehicle.setStatus(VehicleStatus.AVAILABLE);
        when(vehicles.findAllById(any())).thenReturn(List.of(vehicle));

        DriversEntity driver = new DriversEntity();
        driver.setId(1L);
        driver.setName("測試司機");
        driver.setIsActive(true);
        when(drivers.findAllById(any())).thenReturn(List.of(driver));
        when(drivers.findById(1L)).thenReturn(Optional.of(driver));

        ReassignDTO dto = new ReassignDTO();
        dto.setDate(LocalDate.of(2026, 10, 1));
        dto.setWarehouseId(1L);
        ReassignDTO.RouteAssignment assignment = new ReassignDTO.RouteAssignment();
        assignment.setVehicleId(1L);
        assignment.setDriverId(1L);
        assignment.setOrderIds(List.of());
        dto.setRoutes(List.of(assignment));

        DispatchService service = new DispatchService(
                orders, vehicles, warehouses, mock(StoresDAO.class), routes, drivers,
                osrm, mock(RouteOptimizer.class), mock(OrderDispatchEligibilityService.class));
        return new TestContext(service, orders, routes, osrm, driver, dto);
    }

    private record TestContext(
            DispatchService service,
            OrdersDAO orders,
            RoutesDAO routes,
            OsrmClient osrm,
            DriversEntity driver,
            ReassignDTO dto
    ) {
    }
}
