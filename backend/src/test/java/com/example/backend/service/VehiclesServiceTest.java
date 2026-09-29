package com.example.backend.service;

import com.example.backend.constants.MileageCorrectionField;
import com.example.backend.constants.VehicleStatus;
import com.example.backend.dao.MileageLogsDAO;
import com.example.backend.dao.VehicleMileageCorrectionsDAO;
import com.example.backend.dao.VehiclesDAO;
import com.example.backend.dto.request.VehicleMileageCorrectionRequestDTO;
import com.example.backend.dto.request.VehiclesDTO;
import com.example.backend.entity.MileageLogsEntity;
import com.example.backend.entity.VehicleMileageCorrectionsEntity;
import com.example.backend.entity.VehiclesEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 車輛的行車紀錄器里程與保養基準：新增時可以填、舊車（空的）可以補一次、有值之後一般修改改不動，
 * 打錯了由主管更正（要寫原因、留紀錄）。
 * 保養間隔和退役總里程是主管訂的規則，隨時可以改，但三個要一起填。
 * 保養計算本身在 VehicleMaintenanceServiceTest，這裡把它 mock 掉。
 */
class VehiclesServiceTest {

    private static final long VEHICLE_ID = 7L;

    private VehiclesDAO vehiclesDAO;
    private VehicleMaintenanceService vehicleMaintenanceService;
    private MileageLogsDAO mileageLogsDAO;
    private VehicleMileageCorrectionsDAO correctionsDAO;
    private VehiclesService service;
    private VehiclesEntity existing;

    @BeforeEach
    void setUp() {
        vehiclesDAO = mock(VehiclesDAO.class);
        vehicleMaintenanceService = mock(VehicleMaintenanceService.class);
        mileageLogsDAO = mock(MileageLogsDAO.class);
        correctionsDAO = mock(VehicleMileageCorrectionsDAO.class);
        when(vehiclesDAO.save(any(VehiclesEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        existing = new VehiclesEntity();
        existing.setId(VEHICLE_ID);
        existing.setPlateNumber("OLD-1");
        when(vehiclesDAO.findByIdForUpdate(VEHICLE_ID)).thenReturn(Optional.of(existing));

        service = new VehiclesService(vehiclesDAO, vehicleMaintenanceService, mileageLogsDAO, correctionsDAO);
    }

    @Test
    void 新車填0公里_大小保基準自動是0() {
        VehiclesDTO dto = vehicle("NEW-1");
        dto.setCurrentOdometerKm(0);

        service.create(dto);

        verify(vehiclesDAO).save(org.mockito.ArgumentMatchers.argThat((VehiclesEntity saved) ->
                saved.getCurrentOdometerKm() == 0
                        && saved.getLastMinorMaintenanceKm() == 0
                        && saved.getLastMajorMaintenanceKm() == 0));
    }

    @Test
    void 新增時狀態走送修流程_不直接設() {
        VehiclesDTO dto = vehicle("NEW-2");
        dto.setCurrentOdometerKm(12000);
        dto.setStatus(VehicleStatus.MINOR_MAINTENANCE);

        service.create(dto);

        verify(vehicleMaintenanceService).changeStatus(any(VehiclesEntity.class), org.mockito.ArgumentMatchers.eq(VehicleStatus.MINOR_MAINTENANCE));
    }

    @Test
    void 舊車的里程和基準是空的_可以補一次() {
        VehiclesDTO dto = vehicle("OLD-1");
        dto.setCurrentOdometerKm(18400);
        dto.setLastMinorMaintenanceKm(17000);
        dto.setLastMajorMaintenanceKm(10000);

        service.update(VEHICLE_ID, dto);

        assertEquals(18400, existing.getCurrentOdometerKm());
        assertEquals(17000, existing.getLastMinorMaintenanceKm());
        assertEquals(10000, existing.getLastMajorMaintenanceKm());
    }

    @Test
    void 已經有值就改不動_原樣送回來不算改() {
        existing.setCurrentOdometerKm(18400);
        existing.setLastMinorMaintenanceKm(17000);

        VehiclesDTO same = vehicle("OLD-1");
        same.setCurrentOdometerKm(18400);
        same.setLastMinorMaintenanceKm(17000);
        service.update(VEHICLE_ID, same);

        VehiclesDTO changed = vehicle("OLD-1");
        changed.setLastMinorMaintenanceKm(18000);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.update(VEHICLE_ID, changed));
        assertEquals("小保基準已經有紀錄，平常只能由出車、收車或保養完成更新；打錯了請用「更正里程」", error.getMessage());
        assertEquals(17000, existing.getLastMinorMaintenanceKm());
    }

    @Test
    void 補的基準不能比目前里程大_也不能沒有里程就先補基準() {
        existing.setCurrentOdometerKm(18400);
        VehiclesDTO ahead = vehicle("OLD-1");
        ahead.setLastMinorMaintenanceKm(19000);
        assertThrows(IllegalArgumentException.class, () -> service.update(VEHICLE_ID, ahead));

        existing.setCurrentOdometerKm(null);
        VehiclesDTO withoutMileage = vehicle("OLD-1");
        withoutMileage.setLastMajorMaintenanceKm(10000);
        assertThrows(IllegalArgumentException.class, () -> service.update(VEHICLE_ID, withoutMileage));
    }

    @Test
    void 司機填錯讓里程比基準小時_改其他欄位不會被擋() {
        // 司機出車填了 16500，比上次小保 17000 還小（照你的規則出車不擋，照司機填的記）
        existing.setCurrentOdometerKm(16500);
        existing.setLastMinorMaintenanceKm(17000);
        VehiclesDTO dto = vehicle("OLD-1");
        dto.setFuelConsumption(8.5);

        service.update(VEHICLE_ID, dto);

        assertEquals(8.5, existing.getFuelConsumption());
    }

    @Test
    void 有保養紀錄或里程更正紀錄的車不能刪_要改成退役() {
        when(vehiclesDAO.findById(VEHICLE_ID)).thenReturn(Optional.of(existing));
        when(vehicleMaintenanceService.hasHistory(VEHICLE_ID)).thenReturn(true);
        assertThrows(IllegalArgumentException.class, () -> service.delete(VEHICLE_ID));

        when(vehicleMaintenanceService.hasHistory(VEHICLE_ID)).thenReturn(false);
        when(correctionsDAO.existsByVehicleId(VEHICLE_ID)).thenReturn(true);
        assertThrows(IllegalArgumentException.class, () -> service.delete(VEHICLE_ID));

        verify(vehiclesDAO, never()).delete(any());
    }

    // ── 主管更正里程 ──────────────────────────────────────

    @Test
    void 更正里程_車沒在跑只改車上_每個數字留一筆紀錄() {
        // 司機收車多打一位數，車上變成 184500
        existing.setCurrentOdometerKm(184500);
        existing.setLastMinorMaintenanceKm(17000);
        when(mileageLogsDAO.findOpenByVehicleForUpdate(VEHICLE_ID)).thenReturn(List.of());

        service.correctMileage(VEHICLE_ID, correction(18450, 17000, null, "  司機收車多打一位數 "), "主管甲");

        assertEquals(18450, existing.getCurrentOdometerKm());
        List<VehicleMileageCorrectionsEntity> saved = savedCorrections();
        // 小保基準跟現在一樣，不算更正
        assertEquals(1, saved.size());
        VehicleMileageCorrectionsEntity record = saved.get(0);
        assertEquals(MileageCorrectionField.CURRENT_ODOMETER, record.getField());
        assertEquals(184500, record.getOldKm());
        assertEquals(18450, record.getNewKm());
        assertEquals("司機收車多打一位數", record.getReason());
        assertEquals("主管甲", record.getCorrectedBy());
        assertNotNull(record.getCorrectedAt());
        assertNull(record.getMileageLogId());
    }

    @Test
    void 更正里程_車在外面跑時_這趟的出車讀數一起改_不然收不了車() {
        existing.setCurrentOdometerKm(184500);
        MileageLogsEntity openTrip = new MileageLogsEntity();
        openTrip.setId(99L);
        openTrip.setStartOdometer(184500);
        when(mileageLogsDAO.findOpenByVehicleForUpdate(VEHICLE_ID)).thenReturn(List.of(openTrip));

        service.correctMileage(VEHICLE_ID, correction(18450, null, null, "司機出車多打一位數"), "主管甲");

        assertEquals(18450, existing.getCurrentOdometerKm());
        assertEquals(18450, openTrip.getStartOdometer());
        verify(mileageLogsDAO).save(openTrip);
        assertEquals(99L, savedCorrections().get(0).getMileageLogId());
    }

    @Test
    void 更正里程_數字都跟現在一樣就不能送() {
        existing.setCurrentOdometerKm(18400);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.correctMileage(VEHICLE_ID, correction(18400, null, null, "沒改"), "主管甲"));

        assertEquals("數字跟現在一樣，沒有要更正的", error.getMessage());
        verify(correctionsDAO, never()).saveAll(any());
    }

    @Test
    void 更正完基準不能比里程大_要一起改就一次送兩個() {
        existing.setCurrentOdometerKm(18400);
        existing.setLastMinorMaintenanceKm(17000);
        when(mileageLogsDAO.findOpenByVehicleForUpdate(VEHICLE_ID)).thenReturn(List.of());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.correctMileage(VEHICLE_ID, correction(16000, null, null, "讀錯"), "主管甲"));
        assertEquals("小保基準不能大於目前的行車紀錄器里程", error.getMessage());
        verify(correctionsDAO, never()).saveAll(any());

        existing.setCurrentOdometerKm(18400);
        existing.setLastMinorMaintenanceKm(17000);
        service.correctMileage(VEHICLE_ID, correction(16000, 15000, null, "讀錯"), "主管甲");

        assertEquals(16000, existing.getCurrentOdometerKm());
        assertEquals(15000, existing.getLastMinorMaintenanceKm());
        assertEquals(2, savedCorrections().size());
    }

    @Test
    void 更正里程_兩趟以上沒收車就不猜要改哪一趟() {
        existing.setCurrentOdometerKm(184500);
        when(mileageLogsDAO.findOpenByVehicleForUpdate(VEHICLE_ID))
                .thenReturn(List.of(new MileageLogsEntity(), new MileageLogsEntity()));

        assertThrows(IllegalArgumentException.class,
                () -> service.correctMileage(VEHICLE_ID, correction(18450, null, null, "多打一位數"), "主管甲"));
        verify(correctionsDAO, never()).saveAll(any());
    }

    @Test
    void 保養間隔跟基準不同_隨時可以改_留白就清掉() {
        existing.setMinorMaintenanceIntervalKm(3000);
        existing.setMajorMaintenanceIntervalKm(20000);
        existing.setRetirementKm(500000);

        VehiclesDTO changed = vehicle("OLD-1");
        changed.setMinorMaintenanceIntervalKm(5000);
        changed.setMajorMaintenanceIntervalKm(40000);
        changed.setRetirementKm(600000);
        service.update(VEHICLE_ID, changed);

        assertEquals(5000, existing.getMinorMaintenanceIntervalKm());
        assertEquals(40000, existing.getMajorMaintenanceIntervalKm());
        assertEquals(600000, existing.getRetirementKm());
        // 改間隔不會動到基準
        assertNull(existing.getLastMinorMaintenanceKm());

        service.update(VEHICLE_ID, vehicle("OLD-1"));

        assertNull(existing.getMinorMaintenanceIntervalKm());
        assertNull(existing.getMajorMaintenanceIntervalKm());
        assertNull(existing.getRetirementKm());
    }

    @Test
    void 保養間隔要三個一起填_大保間隔不能比小保短() {
        VehiclesDTO partial = vehicle("OLD-1");
        partial.setMinorMaintenanceIntervalKm(5000);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.update(VEHICLE_ID, partial));
        assertEquals("小保間隔、大保間隔、退役總里程要一起填", error.getMessage());

        VehiclesDTO reversed = vehicle("NEW-3");
        reversed.setMinorMaintenanceIntervalKm(5000);
        reversed.setMajorMaintenanceIntervalKm(4000);
        reversed.setRetirementKm(600000);
        error = assertThrows(IllegalArgumentException.class, () -> service.create(reversed));
        assertEquals("大保間隔不能小於小保間隔", error.getMessage());
        verify(vehiclesDAO, never()).save(any());
    }

    private VehicleMileageCorrectionRequestDTO correction(Integer current, Integer minor, Integer major, String reason) {
        VehicleMileageCorrectionRequestDTO dto = new VehicleMileageCorrectionRequestDTO();
        dto.setCurrentOdometerKm(current);
        dto.setLastMinorMaintenanceKm(minor);
        dto.setLastMajorMaintenanceKm(major);
        dto.setReason(reason);
        return dto;
    }

    @SuppressWarnings("unchecked")
    private List<VehicleMileageCorrectionsEntity> savedCorrections() {
        ArgumentCaptor<List<VehicleMileageCorrectionsEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(correctionsDAO, atLeastOnce()).saveAll(captor.capture());
        return captor.getValue();
    }

    private VehiclesDTO vehicle(String plateNumber) {
        VehiclesDTO dto = new VehiclesDTO();
        dto.setPlateNumber(plateNumber);
        dto.setWarehouseId(1L);
        dto.setCapacity(50);
        dto.setStatus(VehicleStatus.AVAILABLE);
        return dto;
    }
}
