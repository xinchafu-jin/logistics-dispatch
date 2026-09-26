package com.example.backend.service;

import com.example.backend.constants.OrderStatus;
import com.example.backend.constants.ScheduleStatus;
import com.example.backend.constants.ShiftType;
import com.example.backend.constants.VehicleStatus;
import com.example.backend.dao.DriverShiftsDAO;
import com.example.backend.dao.DriversDAO;
import com.example.backend.dao.OrdersDAO;
import com.example.backend.dao.RoutesDAO;
import com.example.backend.dao.ScheduleMonthsDAO;
import com.example.backend.dao.VehiclesDAO;
import com.example.backend.dto.request.OptimizeSlotsDTO;
import com.example.backend.entity.DriverShiftsEntity;
import com.example.backend.entity.DriversEntity;
import com.example.backend.entity.OrdersEntity;
import com.example.backend.entity.RoutesEntity;
import com.example.backend.entity.ScheduleMonthsEntity;
import com.example.backend.entity.VehiclesEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 格子全空時自動挑人挑車：車從容量大的開始加到裝得下，司機照 id 配上去，不多拿人、不排沒人開的車。
 *
 * <p>倉庫 1 有三台可用車：TN-2002（20 箱）、TN-2003（15 箱）、TN-2001（10 箱）。
 * 2026-09 的班表預設已發布，每個測試自己放訂單、班次、司機、別倉的路線。</p>
 */
class DispatchSlotServiceAutoTest {

    private static final LocalDate DATE = LocalDate.of(2026, 9, 26);
    private static final Long WAREHOUSE = 1L;

    private final List<OrdersEntity> orders = new ArrayList<>();
    private final List<DriverShiftsEntity> shifts = new ArrayList<>();
    private final List<DriversEntity> drivers = new ArrayList<>();
    private final List<RoutesEntity> routesWithDriver = new ArrayList<>();
    private ScheduleMonthsDAO scheduleMonthsDAO;
    private DispatchSlotService service;

    @BeforeEach
    void setUp() {
        VehiclesDAO vehiclesDAO = mock(VehiclesDAO.class);
        RoutesDAO routesDAO = mock(RoutesDAO.class);
        DriversDAO driversDAO = mock(DriversDAO.class);
        OrdersDAO ordersDAO = mock(OrdersDAO.class);
        DriverShiftsDAO driverShiftsDAO = mock(DriverShiftsDAO.class);
        scheduleMonthsDAO = mock(ScheduleMonthsDAO.class);

        // 故意不照容量排好，確認 autoSlots 自己會排序
        when(vehiclesDAO.findByWarehouseIdAndStatus(WAREHOUSE, VehicleStatus.AVAILABLE)).thenReturn(List.of(
                vehicle(11L, "TN-2001", 10), vehicle(12L, "TN-2002", 20), vehicle(13L, "TN-2003", 15)));
        when(ordersDAO.findByDeliveryDateAndWarehouseId(DATE, WAREHOUSE)).thenReturn(orders);
        when(driverShiftsDAO.findAllByWorkDate(DATE)).thenReturn(shifts);
        when(routesDAO.findByDateAndDriverIdIsNotNull(DATE)).thenReturn(routesWithDriver);
        // 照傳進來的 id 回司機，模擬資料庫只查班表上班的那幾位
        when(driversDAO.findAllById(any())).thenAnswer(invocation -> {
            Collection<?> ids = invocation.getArgument(0);
            return drivers.stream().filter(driver -> ids.contains(driver.getId())).toList();
        });
        givenMonth(ScheduleStatus.PUBLISHED);

        service = new DispatchSlotService(vehiclesDAO, routesDAO, driversDAO, ordersDAO, scheduleMonthsDAO, driverShiftsDAO);
    }

    @Test
    void 從大車加到裝得下_司機照id配() {
        order(OrderStatus.CONFIRMED, 35);
        workingDriver(3L, "陳大明", true);
        workingDriver(1L, "王小明", true);
        workingDriver(2L, "李大華", true);

        DispatchSlotService.AutoSlots auto = service.autoSlots(DATE, WAREHOUSE);

        // 20 + 15 = 35 剛好裝得下，只要兩台，第三位司機留給別倉
        assertEquals(2, auto.getSlots().size());
        assertSlot(auto.getSlots().get(0), 1L, 12L);
        assertSlot(auto.getSlots().get(1), 2L, 13L);
        assertTrue(auto.getNotices().get(0).startsWith("格子空白，自動選了 2 位司機、2 台車"), auto.getNotices().toString());
    }

    @Test
    void 只算已確認的單() {
        order(OrderStatus.CONFIRMED, 18);
        order(OrderStatus.CANCELLED, 30);
        order(OrderStatus.PENDING_CONFIRM, 30);
        order(OrderStatus.COMPLETED, 30);
        workingDriver(1L, "王小明", true);
        workingDriver(2L, "李大華", true);

        DispatchSlotService.AutoSlots auto = service.autoSlots(DATE, WAREHOUSE);

        // 只有 18 箱，一台 20 箱的車就夠
        assertEquals(1, auto.getSlots().size());
    }

    @Test
    void 沒有已確認的單_擋下() {
        order(OrderStatus.PENDING_CONFIRM, 30);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.autoSlots(DATE, WAREHOUSE));
        assertTrue(e.getMessage().contains("沒有已確認的訂單"), e.getMessage());
    }

    @Test
    void 司機不夠_只用司機人數台車並提示() {
        order(OrderStatus.CONFIRMED, 45);
        workingDriver(1L, "王小明", true);

        DispatchSlotService.AutoSlots auto = service.autoSlots(DATE, WAREHOUSE);

        assertEquals(1, auto.getSlots().size());
        assertSlot(auto.getSlots().get(0), 1L, 12L);
        assertTrue(auto.getNotices().contains("需要 3 台車，但可以派的司機只有 1 位，排不下的單會留在待排單"),
                auto.getNotices().toString());
    }

    @Test
    void 全部車都裝不下_照樣排並提示() {
        order(OrderStatus.CONFIRMED, 60);
        workingDriver(1L, "王小明", true);
        workingDriver(2L, "李大華", true);
        workingDriver(3L, "陳大明", true);

        DispatchSlotService.AutoSlots auto = service.autoSlots(DATE, WAREHOUSE);

        assertEquals(3, auto.getSlots().size());
        assertTrue(auto.getNotices().contains("全部車的容量 45 箱裝不下 60 箱，排不下的單會留在待排單"),
                auto.getNotices().toString());
    }

    @Test
    void 不選休假_停用_別倉排走的人_這一倉草稿上的人照選() {
        order(OrderStatus.CONFIRMED, 50);
        shift(1L, ShiftType.DAY_OFF);
        driver(1L, "王小明", true);
        workingDriver(2L, "李大華", false);
        workingDriver(3L, "陳大明", true);
        routeWithDriver(2L, 3L);
        workingDriver(4L, "林志偉", true);
        routeWithDriver(WAREHOUSE, 4L);

        DispatchSlotService.AutoSlots auto = service.autoSlots(DATE, WAREHOUSE);

        assertEquals(List.of(4L), auto.getSlots().stream().map(OptimizeSlotsDTO.Slot::getDriverId).toList());
    }

    @Test
    void 沒有能派的司機_擋下() {
        order(OrderStatus.CONFIRMED, 10);
        shift(1L, ShiftType.LEAVE);
        driver(1L, "王小明", true);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.autoSlots(DATE, WAREHOUSE));
        assertTrue(e.getMessage().contains("沒有可以派的司機"), e.getMessage());
    }

    @Test
    void 月班表還是草稿_擋下() {
        givenMonth(ScheduleStatus.DRAFT);
        order(OrderStatus.CONFIRMED, 10);
        workingDriver(1L, "王小明", true);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.autoSlots(DATE, WAREHOUSE));
        assertTrue(e.getMessage().contains("2026-09 的班表尚未發布"), e.getMessage());
    }

    // ── 測試資料 ─────────────────────────────────────────

    private void assertSlot(OptimizeSlotsDTO.Slot slot, Long driverId, Long vehicleId) {
        assertEquals(driverId, slot.getDriverId());
        assertEquals(vehicleId, slot.getVehicleId());
    }

    private void givenMonth(ScheduleStatus status) {
        ScheduleMonthsEntity month = new ScheduleMonthsEntity();
        month.setStatus(status);
        when(scheduleMonthsDAO.findByScheduleMonth(LocalDate.of(2026, 9, 1))).thenReturn(Optional.of(month));
    }

    private void order(OrderStatus status, int boxes) {
        OrdersEntity order = new OrdersEntity();
        order.setStatus(status);
        order.setBoxCount(boxes);
        orders.add(order);
    }

    private void workingDriver(Long id, String name, boolean active) {
        shift(id, ShiftType.WORK);
        driver(id, name, active);
    }

    private void shift(Long driverId, ShiftType type) {
        DriverShiftsEntity shift = new DriverShiftsEntity();
        shift.setDriverId(driverId);
        shift.setShiftType(type);
        shifts.add(shift);
    }

    private void driver(Long id, String name, boolean active) {
        DriversEntity driver = new DriversEntity();
        driver.setId(id);
        driver.setName(name);
        driver.setIsActive(active);
        drivers.add(driver);
    }

    private void routeWithDriver(Long warehouseId, Long driverId) {
        RoutesEntity route = new RoutesEntity();
        route.setWarehouseId(warehouseId);
        route.setDriverId(driverId);
        routesWithDriver.add(route);
    }

    private VehiclesEntity vehicle(Long id, String plate, int capacity) {
        VehiclesEntity vehicle = new VehiclesEntity();
        vehicle.setId(id);
        vehicle.setPlateNumber(plate);
        vehicle.setCapacity(capacity);
        vehicle.setWarehouseId(WAREHOUSE);
        vehicle.setStatus(VehicleStatus.AVAILABLE);
        return vehicle;
    }
}
