package com.example.backend.service;

import com.example.backend.constants.OrderStatus;
import com.example.backend.constants.RouteStatus;
import com.example.backend.constants.ScheduleStatus;
import com.example.backend.constants.ShiftType;
import com.example.backend.dao.*;
import com.example.backend.entity.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 發布前檢查：司機、超載，以及司機當天的班表。
 *
 * <p>DAO 全部用 mock。每條路線是一台車、一位司機、一張訂單，2026-09 的月班表預設已發布。</p>
 */
class DispatchGuardServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 9, 14);

    private final List<RoutesEntity> routes = new ArrayList<>();
    private final List<DriverShiftsEntity> shifts = new ArrayList<>();
    private final List<DriversEntity> drivers = new ArrayList<>();

    private OrdersDAO ordersDAO;
    private VehiclesDAO vehiclesDAO;
    private ScheduleMonthsDAO scheduleMonthsDAO;
    private DispatchGuardService guard;

    @BeforeEach
    void setUp() {
        RoutesDAO routesDAO = mock(RoutesDAO.class);
        DriverShiftsDAO driverShiftsDAO = mock(DriverShiftsDAO.class);
        DriversDAO driversDAO = mock(DriversDAO.class);
        ordersDAO = mock(OrdersDAO.class);
        vehiclesDAO = mock(VehiclesDAO.class);
        scheduleMonthsDAO = mock(ScheduleMonthsDAO.class);

        when(routesDAO.findByDate(DATE)).thenReturn(routes);
        when(driverShiftsDAO.findAllByWorkDate(DATE)).thenReturn(shifts);
        when(driversDAO.findAllById(any())).thenReturn(drivers);
        givenMonth(ScheduleStatus.PUBLISHED);

        guard = new DispatchGuardService(driverShiftsDAO, driversDAO, routesDAO, ordersDAO, vehiclesDAO, scheduleMonthsDAO);
    }

    @Test
    void 上班的司機可以派出() {
        givenDriver(1L, "王小明", true);
        givenShift(1L, ShiftType.WORK, null);
        givenRoute(10L, "TN-2001", 1L, 5, OrderStatus.CONFIRMED);

        assertDoesNotThrow(() -> guard.assertCanPublish(DATE));
    }

    @Test
    void 休假_請假_尚未安排都擋下_訊息有車牌與姓名() {
        givenDriver(1L, "王小明", true);
        givenDriver(2L, "李大華", true);
        givenDriver(3L, "陳阿明", true);
        givenShift(1L, ShiftType.DAY_OFF, null);
        givenShift(2L, ShiftType.LEAVE, "感冒");
        givenShift(3L, ShiftType.UNASSIGNED, null);
        givenRoute(10L, "TN-2001", 1L, 5, OrderStatus.CONFIRMED);
        givenRoute(11L, "TN-2002", 2L, 5, OrderStatus.CONFIRMED);
        givenRoute(12L, "TN-2003", 3L, 5, OrderStatus.CONFIRMED);

        String message = publishFailure();

        // 可以預派未來幾天，訊息要說是哪一天，不能只寫「今天」
        assertTrue(message.startsWith("2026-09-14 發布前檢查失敗："), message);
        assertTrue(message.contains("TN-2001（王小明）：當天休假"), message);
        assertTrue(message.contains("TN-2002（李大華）：當天請假（感冒）"), message);
        assertTrue(message.contains("TN-2003（陳阿明）：當天尚未安排"), message);
    }

    @Test
    void 當天沒有班次_擋下() {
        givenDriver(1L, "王小明", true);
        givenRoute(10L, "TN-2001", 1L, 5, OrderStatus.CONFIRMED);

        assertTrue(publishFailure().contains("TN-2001（王小明）：當天未排班"));
    }

    @Test
    void 帳號停用_即使當天上班也擋下() {
        givenDriver(1L, "王小明", false);
        givenShift(1L, ShiftType.WORK, null);
        givenRoute(10L, "TN-2001", 1L, 5, OrderStatus.CONFIRMED);

        assertTrue(publishFailure().contains("TN-2001（王小明）：帳號已停用"));
    }

    @Test
    void 月班表還是草稿_整體只列一次() {
        givenMonth(ScheduleStatus.DRAFT);
        givenDriver(1L, "王小明", true);
        givenDriver(2L, "李大華", true);
        givenShift(1L, ShiftType.WORK, null);
        givenShift(2L, ShiftType.WORK, null);
        givenRoute(10L, "TN-2001", 1L, 5, OrderStatus.CONFIRMED);
        givenRoute(11L, "TN-2002", 2L, 5, OrderStatus.CONFIRMED);

        String message = publishFailure();

        assertEquals(1, message.split("班表尚未發布", -1).length - 1, message);
        assertTrue(message.contains("2026-09 的班表尚未發布"), message);
    }

    @Test
    void 月班表不存在_也擋下() {
        when(scheduleMonthsDAO.findByScheduleMonth(LocalDate.of(2026, 9, 1))).thenReturn(Optional.empty());
        givenDriver(1L, "王小明", true);
        givenShift(1L, ShiftType.WORK, null);
        givenRoute(10L, "TN-2001", 1L, 5, OrderStatus.CONFIRMED);

        assertTrue(publishFailure().contains("2026-09 的班表尚未發布"));
    }

    @Test
    void 沒司機_休假_超載_一次列出() {
        givenDriver(2L, "李大華", true);
        givenDriver(3L, "陳阿明", true);
        givenShift(2L, ShiftType.DAY_OFF, null);
        givenShift(3L, ShiftType.WORK, null);
        givenRoute(10L, "TN-2001", null, 5, OrderStatus.CONFIRMED);
        givenRoute(11L, "TN-2002", 2L, 5, OrderStatus.CONFIRMED);
        givenRoute(12L, "TN-2003", 3L, 12, OrderStatus.CONFIRMED);

        String message = publishFailure();

        assertTrue(message.contains("TN-2001 尚未指派司機"), message);
        assertTrue(message.contains("TN-2002（李大華）：當天休假"), message);
        assertTrue(message.contains("車輛 TN-2003 裝載 12 箱，超過容量 10 箱"), message);
    }

    @Test
    void 已發布或沒有有效訂單的路線_不檢查() {
        givenDriver(1L, "王小明", true);
        givenDriver(2L, "李大華", true);
        givenDriver(3L, "陳阿明", true);
        givenShift(1L, ShiftType.DAY_OFF, null);
        givenShift(2L, ShiftType.DAY_OFF, null);
        givenShift(3L, ShiftType.WORK, null);
        givenRoute(10L, "TN-2001", 1L, 5, OrderStatus.CONFIRMED).setStatus(RouteStatus.PUBLISHED);
        givenRoute(11L, "TN-2002", 2L, 5, OrderStatus.COMPLETED);
        givenRoute(12L, "TN-2003", 3L, 5, OrderStatus.CONFIRMED);

        assertDoesNotThrow(() -> guard.assertCanPublish(DATE));
    }

    @Test
    void 撤回_已開始配送就擋下() {
        givenRoute(10L, "TN-2001", 1L, 5, OrderStatus.IN_DELIVERY).setStatus(RouteStatus.PUBLISHED);
        OrdersEntity delivering = new OrdersEntity();
        delivering.setOrderNumber("DO-001");
        delivering.setStatus(OrderStatus.IN_DELIVERY);
        when(ordersDAO.findByRouteIdIn(List.of(10L))).thenReturn(List.of(delivering));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> guard.assertCanWithdraw(DATE));
        assertTrue(e.getMessage().contains("DO-001"), e.getMessage());
    }

    @Test
    void 撤回_已點交就擋下_貨已經在車上() {
        givenRoute(10L, "TN-2001", 1L, 5, OrderStatus.LOADED).setStatus(RouteStatus.PUBLISHED);
        OrdersEntity loaded = new OrdersEntity();
        loaded.setOrderNumber("DO-002");
        loaded.setStatus(OrderStatus.LOADED);
        when(ordersDAO.findByRouteIdIn(List.of(10L))).thenReturn(List.of(loaded));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> guard.assertCanWithdraw(DATE));
        assertTrue(e.getMessage().contains("DO-002"), e.getMessage());
    }

    @Test
    void 撤回_還沒出發可以撤回() {
        givenRoute(10L, "TN-2001", 1L, 5, OrderStatus.CONFIRMED).setStatus(RouteStatus.PUBLISHED);
        OrdersEntity waiting = new OrdersEntity();
        waiting.setOrderNumber("DO-001");
        waiting.setStatus(OrderStatus.CONFIRMED);
        when(ordersDAO.findByRouteIdIn(List.of(10L))).thenReturn(List.of(waiting));

        assertDoesNotThrow(() -> guard.assertCanWithdraw(DATE));
    }

    @Test
    void 撤回_三筆已完成和一筆待送不應鎖住整天() {
        givenRoute(10L, "TN-2001", 1L, 5, OrderStatus.CONFIRMED).setStatus(RouteStatus.PUBLISHED);
        List<OrdersEntity> orders = new ArrayList<>();
        for (int index = 0; index < 4; index++) {
            OrdersEntity order = new OrdersEntity();
            order.setOrderNumber("DO-" + index);
            order.setStatus(index < 3 ? OrderStatus.COMPLETED : OrderStatus.CONFIRMED);
            order.setAssignedDriverId(1L);
            order.setAssignedVehicleId(110L);
            orders.add(order);
        }
        when(ordersDAO.findByRouteIdIn(List.of(10L))).thenReturn(orders);

        assertDoesNotThrow(() -> guard.assertCanWithdraw(DATE));
        assertEquals(3, orders.stream().filter(order -> order.getStatus() == OrderStatus.COMPLETED).count());
        assertTrue(orders.stream().allMatch(order -> order.getAssignedDriverId().equals(1L)));
    }

    @Test
    void 撤回_已結案的失敗和未簽收不會阻擋待送訂單() {
        givenRoute(10L, "TN-2001", 1L, 5, OrderStatus.CONFIRMED).setStatus(RouteStatus.PUBLISHED);
        OrdersEntity failed = new OrdersEntity();
        failed.setStatus(OrderStatus.FAILED);
        OrdersEntity noSignature = new OrdersEntity();
        noSignature.setStatus(OrderStatus.NO_SIGNATURE);
        when(ordersDAO.findByRouteIdIn(List.of(10L))).thenReturn(List.of(failed, noSignature));

        assertDoesNotThrow(() -> guard.assertCanWithdraw(DATE));
        assertEquals(OrderStatus.FAILED, failed.getStatus());
        assertEquals(OrderStatus.NO_SIGNATURE, noSignature.getStatus());
    }

    private String publishFailure() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> guard.assertCanPublish(DATE));
        return e.getMessage();
    }

    private void givenMonth(ScheduleStatus status) {
        ScheduleMonthsEntity month = new ScheduleMonthsEntity();
        month.setStatus(status);
        when(scheduleMonthsDAO.findByScheduleMonth(LocalDate.of(2026, 9, 1))).thenReturn(Optional.of(month));
    }

    private void givenDriver(Long id, String name, boolean active) {
        DriversEntity driver = new DriversEntity();
        driver.setId(id);
        driver.setName(name);
        driver.setIsActive(active);
        drivers.add(driver);
    }

    private void givenShift(Long driverId, ShiftType type, String reason) {
        DriverShiftsEntity shift = new DriverShiftsEntity();
        shift.setDriverId(driverId);
        shift.setWorkDate(DATE);
        shift.setShiftType(type);
        shift.setChangeReason(reason);
        shifts.add(shift);
    }

    /** 草稿路線，車輛容量 10 箱，車輛 id 用路線 id + 100 */
    private RoutesEntity givenRoute(Long routeId, String plate, Long driverId, int boxes, OrderStatus orderStatus) {
        RoutesEntity route = new RoutesEntity();
        route.setId(routeId);
        route.setDate(DATE);
        route.setVehicleId(routeId + 100);
        route.setDriverId(driverId);
        route.setStatus(RouteStatus.DRAFT);
        routes.add(route);

        OrdersEntity order = new OrdersEntity();
        order.setRouteId(routeId);
        order.setStatus(orderStatus);
        order.setBoxCount(boxes);
        when(ordersDAO.findByRouteIdOrderBySequence(routeId)).thenReturn(List.of(order));

        VehiclesEntity vehicle = new VehiclesEntity();
        vehicle.setId(routeId + 100);
        vehicle.setPlateNumber(plate);
        vehicle.setCapacity(10);
        when(vehiclesDAO.findById(routeId + 100)).thenReturn(Optional.of(vehicle));
        return route;
    }
}
