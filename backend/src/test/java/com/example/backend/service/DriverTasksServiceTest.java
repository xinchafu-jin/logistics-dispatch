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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DriverTasksServiceTest {

    @Test
    void 指定日期任務只查詢登入司機該日已發布路線() {
        DriversDAO driversDAO = mock(DriversDAO.class);
        RoutesDAO routesDAO = mock(RoutesDAO.class);
        DriverTasksService service = new DriverTasksService(
                driversDAO, routesDAO, mock(OrdersDAO.class), mock(WarehousesDAO.class),
                mock(VehiclesDAO.class), mock(StoresDAO.class));

        DriversEntity driver = new DriversEntity();
        driver.setId(7L);
        driver.setName("司機");
        driver.setIsActive(true);
        when(driversDAO.findById(7L)).thenReturn(Optional.of(driver));

        LocalDate selectedDate = LocalDate.of(2026, 10, 1);
        when(routesDAO.findByDateAndDriverIdAndStatusOrderByIdAsc(
                selectedDate, 7L, RouteStatus.PUBLISHED)).thenReturn(List.of());

        var response = service.findByDate(7L, selectedDate);

        assertEquals(selectedDate, response.getDate());
        assertEquals(7L, response.getDriverId());
        assertTrue(response.getRoutes().isEmpty());
        verify(routesDAO).findByDateAndDriverIdAndStatusOrderByIdAsc(
                selectedDate, 7L, RouteStatus.PUBLISHED);
    }

    @Test
    void 過去日期仍會顯示已完成的配送任務() {
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
        driver.setName("司機");
        driver.setIsActive(true);
        when(driversDAO.findById(7L)).thenReturn(Optional.of(driver));

        LocalDate date = LocalDate.now(ZoneId.of("Asia/Taipei")).minusDays(1);
        RoutesEntity route = futureRoute(31L, date, 1L, 11L);
        when(routesDAO.findByDateAndDriverIdAndStatusOrderByIdAsc(
                date, 7L, RouteStatus.PUBLISHED)).thenReturn(List.of(route));
        OrdersEntity order = futureOrder(41L, 31L, 1L, 21L, "DO-COMPLETE");
        order.setStatus(OrderStatus.COMPLETED);
        when(ordersDAO.findByRouteIdIn(anyList())).thenReturn(List.of(order));
        when(warehousesDAO.findAllById(any())).thenReturn(List.of(warehouse(1L, "出發倉")));
        when(vehiclesDAO.findAllById(any())).thenReturn(List.of(vehicle(11L, "ABC-1234")));
        when(storesDAO.findAllById(any())).thenReturn(List.of(store(21L, "門市")));

        var response = service.findByDate(7L, date);

        assertEquals(1, response.getRoutes().size());
        assertEquals(OrderStatus.COMPLETED, response.getRoutes().getFirst().getStops().getFirst().getOrderStatus());
    }

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

    @Test
    void 未來任務會依日期分組並顯示每天的出發倉庫() {
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
        driver.setWarehouseId(1L);
        when(driversDAO.findById(7L)).thenReturn(Optional.of(driver));

        LocalDate today = LocalDate.now(ZoneId.of("Asia/Taipei"));
        RoutesEntity northRoute = futureRoute(31L, today.plusDays(1), 1L, 11L);
        RoutesEntity southRoute = futureRoute(32L, today.plusDays(2), 2L, 12L);
        when(routesDAO.findByDateGreaterThanAndDriverIdAndStatusOrderByDateAscIdAsc(
                today, 7L, RouteStatus.PUBLISHED)).thenReturn(List.of(northRoute, southRoute));

        OrdersEntity northOrder = futureOrder(41L, 31L, 1L, 21L, "DO-NORTH");
        OrdersEntity southOrder = futureOrder(42L, 32L, 2L, 22L, "DO-SOUTH");
        when(ordersDAO.findByRouteIdIn(anyList())).thenAnswer(invocation -> {
            List<Long> routeIds = invocation.getArgument(0);
            return routeIds.contains(31L) ? List.of(northOrder) : List.of(southOrder);
        });

        WarehousesEntity northWarehouse = warehouse(1L, "北區倉");
        WarehousesEntity southWarehouse = warehouse(2L, "南區倉");
        when(warehousesDAO.findAllById(any())).thenReturn(List.of(northWarehouse, southWarehouse));

        VehiclesEntity northVehicle = vehicle(11L, "NORTH-11");
        VehiclesEntity southVehicle = vehicle(12L, "SOUTH-12");
        when(vehiclesDAO.findAllById(any())).thenReturn(List.of(northVehicle, southVehicle));

        StoresEntity northStore = store(21L, "北區門市");
        StoresEntity southStore = store(22L, "南區門市");
        when(storesDAO.findAllById(any())).thenReturn(List.of(northStore, southStore));

        var response = service.findUpcoming(7L);

        assertEquals(2, response.size());
        assertEquals(today.plusDays(1), response.get(0).getDate());
        assertEquals("北區倉", response.get(0).getRoutes().getFirst().getWarehouse().getName());
        assertEquals(today.plusDays(2), response.get(1).getDate());
        assertEquals("南區倉", response.get(1).getRoutes().getFirst().getWarehouse().getName());
        assertEquals(1, response.get(0).getRoutes().size());
        assertEquals(1, response.get(1).getRoutes().size());
    }

    private RoutesEntity futureRoute(Long id, LocalDate date, Long warehouseId, Long vehicleId) {
        RoutesEntity route = new RoutesEntity();
        route.setId(id);
        route.setDate(date);
        route.setDriverId(7L);
        route.setWarehouseId(warehouseId);
        route.setVehicleId(vehicleId);
        route.setStatus(RouteStatus.PUBLISHED);
        return route;
    }

    private OrdersEntity futureOrder(
            Long id,
            Long routeId,
            Long warehouseId,
            Long storeId,
            String orderNumber
    ) {
        OrdersEntity order = new OrdersEntity();
        order.setId(id);
        order.setRouteId(routeId);
        order.setWarehouseId(warehouseId);
        order.setStoreId(storeId);
        order.setOrderNumber(orderNumber);
        order.setSequence(1);
        order.setBoxCount(5);
        order.setStatus(OrderStatus.CONFIRMED);
        return order;
    }

    private WarehousesEntity warehouse(Long id, String name) {
        WarehousesEntity warehouse = new WarehousesEntity();
        warehouse.setId(id);
        warehouse.setName(name);
        warehouse.setAddress(name + "地址");
        return warehouse;
    }

    private VehiclesEntity vehicle(Long id, String plateNumber) {
        VehiclesEntity vehicle = new VehiclesEntity();
        vehicle.setId(id);
        vehicle.setPlateNumber(plateNumber);
        vehicle.setCapacity(100);
        return vehicle;
    }

    private StoresEntity store(Long id, String name) {
        StoresEntity store = new StoresEntity();
        store.setId(id);
        store.setName(name);
        store.setAddress(name + "地址");
        return store;
    }
}
