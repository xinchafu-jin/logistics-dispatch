package com.example.backend.service;

import com.example.backend.constants.VehicleStatus;
import com.example.backend.dao.DriversDAO;
import com.example.backend.dao.RoutesDAO;
import com.example.backend.dao.VehiclesDAO;
import com.example.backend.dto.request.OptimizeSlotsDTO;
import com.example.backend.entity.DriversEntity;
import com.example.backend.entity.RoutesEntity;
import com.example.backend.entity.VehiclesEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 格子轉成排車計畫：以人為準、只填司機的格子自動配車、有問題的格子略過並提醒。
 *
 * <p>倉庫 1 有三台可用車：TN-2001（10 箱）、TN-2002（20 箱）、TN-2003（15 箱）。</p>
 */
class DispatchSlotServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 9, 14);
    private static final Long WAREHOUSE = 1L;

    private final List<RoutesEntity> routesWithDriver = new ArrayList<>();
    private final List<DriversEntity> drivers = new ArrayList<>();
    private DispatchSlotService service;

    @BeforeEach
    void setUp() {
        VehiclesDAO vehiclesDAO = mock(VehiclesDAO.class);
        RoutesDAO routesDAO = mock(RoutesDAO.class);
        DriversDAO driversDAO = mock(DriversDAO.class);
        when(vehiclesDAO.findByWarehouseIdAndStatus(WAREHOUSE, VehicleStatus.AVAILABLE)).thenReturn(List.of(
                vehicle(11L, "TN-2001", 10), vehicle(12L, "TN-2002", 20), vehicle(13L, "TN-2003", 15)));
        when(routesDAO.findByDateAndDriverIdIsNotNull(DATE)).thenReturn(routesWithDriver);
        when(driversDAO.findAllById(any())).thenReturn(drivers);
        drivers.add(driver(1L, "王小明"));
        drivers.add(driver(2L, "李大華"));
        service = new DispatchSlotService(vehiclesDAO, routesDAO, driversDAO);
    }

    @Test
    void 只填司機_配容量最大且沒被選走的車() {
        DispatchSlotService.SlotPlan plan = service.plan(DATE, WAREHOUSE, List.of(
                slot(1L, null),
                slot(null, 12L)));

        // TN-2002（20 箱）已被第二格選走，王小明配到剩下最大的 TN-2003（15 箱）
        assertEquals(List.of(13L, 12L), plan.getVehicleIds());
        assertEquals(1L, plan.getDriverByVehicle().get(13L));
        assertNull(plan.getDriverByVehicle().get(12L));
        assertTrue(plan.getNotices().contains("王小明 自動配車 TN-2003"), plan.getNotices().toString());
    }

    @Test
    void 人車都有_照格子帶入() {
        DispatchSlotService.SlotPlan plan = service.plan(DATE, WAREHOUSE, List.of(slot(2L, 11L)));

        assertEquals(List.of(11L), plan.getVehicleIds());
        assertEquals(2L, plan.getDriverByVehicle().get(11L));
        assertTrue(plan.getNotices().isEmpty());
    }

    @Test
    void 沒有車可以配_這格略過並提醒() {
        DispatchSlotService.SlotPlan plan = service.plan(DATE, WAREHOUSE, List.of(
                slot(null, 11L), slot(null, 12L), slot(null, 13L), slot(1L, null)));

        assertEquals(3, plan.getVehicleIds().size());
        assertTrue(plan.getNotices().contains("王小明 沒有可以配的車，這格略過"), plan.getNotices().toString());
    }

    @Test
    void 選了不能出車的車_這格略過並提醒() {
        DispatchSlotService.SlotPlan plan = service.plan(DATE, WAREHOUSE, List.of(slot(null, 99L), slot(2L, 11L)));

        assertEquals(List.of(11L), plan.getVehicleIds());
        assertTrue(plan.getNotices().get(0).contains("車輛 99 目前不能出車"), plan.getNotices().toString());
    }

    @Test
    void 司機已排在別倉_車照排但不帶司機() {
        RoutesEntity elsewhere = new RoutesEntity();
        elsewhere.setWarehouseId(2L);
        elsewhere.setDriverId(1L);
        routesWithDriver.add(elsewhere);

        DispatchSlotService.SlotPlan plan = service.plan(DATE, WAREHOUSE, List.of(slot(1L, 11L)));

        assertEquals(List.of(11L), plan.getVehicleIds());
        assertTrue(plan.getDriverByVehicle().isEmpty());
        assertTrue(plan.getNotices().get(0).contains("王小明 當天已排在其他倉"), plan.getNotices().toString());
    }

    @Test
    void 同一位司機放兩格_擋下() {
        assertThrows(IllegalArgumentException.class,
                () -> service.plan(DATE, WAREHOUSE, List.of(slot(1L, 11L), slot(1L, 12L))));
    }

    @Test
    void 全部都是空格_擋下() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.plan(DATE, WAREHOUSE, List.of(slot(null, null), slot(null, null))));
        assertTrue(e.getMessage().contains("至少要有一格"), e.getMessage());
    }

    private OptimizeSlotsDTO.Slot slot(Long driverId, Long vehicleId) {
        OptimizeSlotsDTO.Slot slot = new OptimizeSlotsDTO.Slot();
        slot.setDriverId(driverId);
        slot.setVehicleId(vehicleId);
        return slot;
    }

    private VehiclesEntity vehicle(Long id, String plate, int capacity) {
        VehiclesEntity vehicle = new VehiclesEntity();
        vehicle.setId(id);
        vehicle.setPlateNumber(plate);
        vehicle.setCapacity(capacity);
        return vehicle;
    }

    private DriversEntity driver(Long id, String name) {
        DriversEntity driver = new DriversEntity();
        driver.setId(id);
        driver.setName(name);
        driver.setIsActive(true);
        return driver;
    }
}
