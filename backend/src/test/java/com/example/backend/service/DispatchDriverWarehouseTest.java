package com.example.backend.service;

import com.example.backend.constants.VehicleStatus;
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
    @Test void rejectsOtherWarehouseAndUnassignedDriverBeforeChangingRoutes() {
        OrdersDAO orders = mock(OrdersDAO.class);
        VehiclesDAO vehicles = mock(VehiclesDAO.class);
        WarehousesDAO warehouses = mock(WarehousesDAO.class);
        RoutesDAO routes = mock(RoutesDAO.class);
        DriversDAO drivers = mock(DriversDAO.class);
        var warehouse = new WarehousesEntity(); warehouse.setId(1L);
        when(warehouses.findById(1L)).thenReturn(Optional.of(warehouse));
        var vehicle = new VehiclesEntity(); vehicle.setId(1L); vehicle.setWarehouseId(1L); vehicle.setStatus(VehicleStatus.AVAILABLE);
        when(vehicles.findAllById(any())).thenReturn(List.of(vehicle));
        var driver = new DriversEntity(); driver.setId(1L); driver.setName("測試司機"); driver.setIsActive(true);
        when(drivers.findAllById(any())).thenReturn(List.of(driver));
        var dto = new ReassignDTO(); dto.setDate(LocalDate.of(2026, 10, 1)); dto.setWarehouseId(1L);
        var assignment = new ReassignDTO.RouteAssignment(); assignment.setVehicleId(1L); assignment.setDriverId(1L); assignment.setOrderIds(List.of());
        dto.setRoutes(List.of(assignment));
        var service = new DispatchService(orders, vehicles, warehouses, mock(StoresDAO.class), routes, drivers,
                mock(OsrmClient.class), mock(RouteOptimizer.class));
        for (Long id : new Long[]{null, 2L}) {
            driver.setWarehouseId(id);
            assertTrue(assertThrows(IllegalArgumentException.class, () -> service.reassign(dto)).getMessage().contains("不屬於目前倉庫"));
        }
        verifyNoInteractions(routes);
        verify(orders, never()).saveAll(any());
    }
}
