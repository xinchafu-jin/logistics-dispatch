package com.example.backend.service;

import com.example.backend.constants.OrderStatus;
import com.example.backend.constants.RouteStatus;
import com.example.backend.dao.DriversDAO;
import com.example.backend.dao.OrdersDAO;
import com.example.backend.dao.RoutesDAO;
import com.example.backend.dao.StoresDAO;
import com.example.backend.dao.VehiclesDAO;
import com.example.backend.dao.WarehousesDAO;
import com.example.backend.entity.DriversEntity;
import com.example.backend.entity.OrderItemsEntity;
import com.example.backend.entity.OrdersEntity;
import com.example.backend.entity.RoutesEntity;
import com.example.backend.entity.StoresEntity;
import com.example.backend.entity.VehiclesEntity;
import com.example.backend.entity.WarehousesEntity;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DriverTasksServiceTest {

    @Test
    void 今日任務會帶出可逐項點交的實際內容物() {
        DriversDAO driversDAO = mock(DriversDAO.class);
        RoutesDAO routesDAO = mock(RoutesDAO.class);
        OrdersDAO ordersDAO = mock(OrdersDAO.class);
        WarehousesDAO warehousesDAO = mock(WarehousesDAO.class);
        VehiclesDAO vehiclesDAO = mock(VehiclesDAO.class);
        StoresDAO storesDAO = mock(StoresDAO.class);
        DriverTasksService service = new DriverTasksService(
                driversDAO, routesDAO, ordersDAO, warehousesDAO, vehiclesDAO, storesDAO);

        DriversEntity driver = new DriversEntity();
        driver.setId(7L);
        driver.setName("測試司機");
        driver.setIsActive(true);
        when(driversDAO.findById(7L)).thenReturn(Optional.of(driver));

        RoutesEntity route = new RoutesEntity();
        route.setId(30L);
        route.setDate(LocalDate.now(ZoneId.of("Asia/Taipei")));
        route.setDriverId(7L);
        route.setWarehouseId(1L);
        route.setVehicleId(2L);
        route.setStatus(RouteStatus.PUBLISHED);
        when(routesDAO.findByDateAndDriverIdAndStatusOrderByIdAsc(
                route.getDate(), 7L, RouteStatus.PUBLISHED)).thenReturn(List.of(route));

        OrdersEntity order = new OrdersEntity();
        order.setId(41L);
        order.setOrderNumber("DO-001");
        order.setRouteId(30L);
        order.setStoreId(3L);
        order.setWarehouseId(1L);
        order.setBoxCount(8);
        order.setStatus(OrderStatus.CONFIRMED);
        OrderItemsEntity item = new OrderItemsEntity();
        item.setId(601L);
        item.setProductCode("TEA-MILK");
        item.setItemName("奶茶");
        item.setExpectedQuantity(3);
        item.setUnit("箱");
        item.setSequence(1);
        order.addItem(item);
        when(ordersDAO.findByRouteIdIn(anyList())).thenReturn(List.of(order));

        WarehousesEntity warehouse = new WarehousesEntity();
        warehouse.setId(1L);
        when(warehousesDAO.findAllById(any())).thenReturn(List.of(warehouse));
        VehiclesEntity vehicle = new VehiclesEntity();
        vehicle.setId(2L);
        vehicle.setCapacity(180);
        when(vehiclesDAO.findAllById(any())).thenReturn(List.of(vehicle));
        StoresEntity store = new StoresEntity();
        store.setId(3L);
        store.setName("測試門市");
        when(storesDAO.findAllById(any())).thenReturn(List.of(store));

        var response = service.findToday(7L);
        var stop = response.getRoutes().getFirst().getStops().getFirst();

        assertTrue(stop.getLoadingRequired());
        assertFalse(stop.getItemChecklistCompleted());
        assertEquals(1, stop.getItems().size());
        assertEquals("奶茶", stop.getItems().getFirst().getItemName());
        assertEquals(3, stop.getItems().getFirst().getExpectedQuantity());
        assertEquals("箱", stop.getItems().getFirst().getUnit());
        assertFalse(stop.getItems().getFirst().getChecked());
    }
}
