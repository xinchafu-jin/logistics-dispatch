package com.example.backend.service;

import com.example.backend.constants.MaintenanceRecordStatus;
import com.example.backend.constants.MaintenanceRecordType;
import com.example.backend.constants.VehicleStatus;
import com.example.backend.dao.MileageLogsDAO;
import com.example.backend.dao.VehicleMaintenanceRecordsDAO;
import com.example.backend.dao.VehicleMaintenanceSettingsDAO;
import com.example.backend.dao.VehiclesDAO;
import com.example.backend.dto.request.VehicleMaintenanceSettingsDTO;
import com.example.backend.dto.respones.VehicleMaintenanceSummaryResponse;
import com.example.backend.entity.MileageLogsEntity;
import com.example.backend.entity.VehicleMaintenanceRecordsEntity;
import com.example.backend.entity.VehicleMaintenanceSettingsEntity;
import com.example.backend.entity.VehiclesEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 保養計算與送修流程。DAO 全部用 mock。
 *
 * <p>車 1 號：行車紀錄器里程 8000；上次小保在 5000、大保在 6000。
 * 這台車自己的規則：每 3000 公里小保、每 20000 公里大保、500000 公里退役；提醒公里數 500。
 * 所以現在離小保剩 0（5000＋3000－8000）、離大保剩 18000、離退役剩 492000。</p>
 */
class VehicleMaintenanceServiceTest {

    private static final long VEHICLE_ID = 1L;

    private VehiclesEntity vehicle;
    private VehicleMaintenanceSettingsEntity settings;
    private VehicleMaintenanceSettingsDAO settingsDAO;
    private VehicleMaintenanceRecordsDAO recordsDAO;
    private MileageLogsDAO mileageLogsDAO;
    private VehicleMaintenanceService service;

    @BeforeEach
    void setUp() {
        vehicle = new VehiclesEntity();
        vehicle.setId(VEHICLE_ID);
        vehicle.setPlateNumber("TEST-1");
        vehicle.setCurrentOdometerKm(8000);
        vehicle.setLastMinorMaintenanceKm(5000);
        vehicle.setLastMajorMaintenanceKm(6000);
        vehicle.setMinorMaintenanceIntervalKm(3000);
        vehicle.setMajorMaintenanceIntervalKm(20000);
        vehicle.setRetirementKm(500000);

        settings = new VehicleMaintenanceSettingsEntity();
        settingsDAO = mock(VehicleMaintenanceSettingsDAO.class);
        recordsDAO = mock(VehicleMaintenanceRecordsDAO.class);
        mileageLogsDAO = mock(MileageLogsDAO.class);
        when(settingsDAO.findById(VehicleMaintenanceSettingsEntity.SINGLE_ROW_ID)).thenReturn(Optional.of(settings));
        when(recordsDAO.findAllByVehicleIdOrderByIdDesc(VEHICLE_ID)).thenReturn(List.of());

        service = new VehicleMaintenanceService(settingsDAO, recordsDAO, mock(VehiclesDAO.class), mileageLogsDAO);
    }

    // ── 計算與結論 ────────────────────────────────────────

    @Test
    void 剛好到保養里程算提醒_不算超過() {
        VehicleMaintenanceSummaryResponse summary = service.summary(vehicle, 0.0);

        assertEquals(0, summary.getMinorRemainingKm());
        assertEquals(0.0, summary.getProjectedMinorKm());
        assertEquals(VehicleMaintenanceSummaryResponse.WARNING, summary.getDecision());
    }

    @Test
    void 跑完這趟會超過就擋_原因寫出超過多少() {
        vehicle.setCurrentOdometerKm(7990);

        VehicleMaintenanceSummaryResponse summary = service.summary(vehicle, 20.0);

        assertEquals(-10.0, summary.getProjectedMinorKm());
        assertEquals(VehicleMaintenanceSummaryResponse.BLOCKED, summary.getDecision());
        assertTrue(summary.getReasons().contains("小保里程將超過 10.0 km"));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.assertCanDispatch(vehicle, 20.0));
        assertEquals("TEST-1 不能出車：小保里程將超過 10.0 km", error.getMessage());
    }

    @Test
    void 超過提醒公里數是正常_提醒公里數存了就照新的算() {
        vehicle.setCurrentOdometerKm(7400);
        assertEquals(VehicleMaintenanceSummaryResponse.NORMAL, service.summary(vehicle, 0.0).getDecision());

        // 提醒改成 700：離小保剩 600，進入提醒範圍
        VehicleMaintenanceSettingsDTO dto = new VehicleMaintenanceSettingsDTO();
        dto.setWarningKm(700);
        assertEquals(700, service.saveSettings(dto).getWarningKm());

        verify(settingsDAO).save(settings);
        assertEquals(VehicleMaintenanceSummaryResponse.WARNING, service.summary(vehicle, 0.0).getDecision());
    }

    @Test
    void 小保_大保_退役任何一項會超過都擋() {
        vehicle.setMinorMaintenanceIntervalKm(40000);
        vehicle.setMajorMaintenanceIntervalKm(2010);
        assertEquals(VehicleMaintenanceSummaryResponse.BLOCKED, service.summary(vehicle, 20.0).getDecision());

        vehicle.setMajorMaintenanceIntervalKm(40000);
        vehicle.setRetirementKm(8010);
        VehicleMaintenanceSummaryResponse summary = service.summary(vehicle, 20.0);
        assertEquals(VehicleMaintenanceSummaryResponse.BLOCKED, summary.getDecision());
        assertEquals(-10.0, summary.getProjectedRetirementKm());
    }

    @Test
    void 每台車照自己的間隔算_改了間隔立刻照新的算() {
        // 另一台車里程和基準都一樣，但小保間隔是 10000
        VehiclesEntity other = new VehiclesEntity();
        other.setPlateNumber("TEST-2");
        other.setCurrentOdometerKm(8000);
        other.setLastMinorMaintenanceKm(5000);
        other.setLastMajorMaintenanceKm(6000);
        other.setMinorMaintenanceIntervalKm(10000);
        other.setMajorMaintenanceIntervalKm(20000);
        other.setRetirementKm(500000);

        assertEquals(0, service.summary(vehicle, null).getMinorRemainingKm());
        assertEquals(7000, service.summary(other, null).getMinorRemainingKm());

        // 間隔存在車上，沒有別的副本：改成 4000 → 5000＋4000－8000＝1000
        vehicle.setMinorMaintenanceIntervalKm(4000);
        assertEquals(1000, service.summary(vehicle, null).getMinorRemainingKm());
    }

    @Test
    void 缺間隔_基準或里程_算不出來只提醒_不擋() {
        // 舊車還沒設定規則：三個都是空的（畫面上只能一起填、一起留白）
        vehicle.setMinorMaintenanceIntervalKm(null);
        vehicle.setMajorMaintenanceIntervalKm(null);
        vehicle.setRetirementKm(null);
        assertUnknownButDispatchable("還沒設定保養間隔與退役里程");

        vehicle.setMinorMaintenanceIntervalKm(3000);
        vehicle.setMajorMaintenanceIntervalKm(20000);
        vehicle.setRetirementKm(500000);
        vehicle.setLastMinorMaintenanceKm(null);
        assertUnknownButDispatchable("還沒有小保基準");

        vehicle.setLastMinorMaintenanceKm(5000);
        vehicle.setCurrentOdometerKm(null);
        assertUnknownButDispatchable("還沒有行車紀錄器里程");
    }

    @Test
    void 預估里程無效也只提醒_不擋() {
        // 離小保還有 1000，不在提醒範圍，結論只看「這趟算不出來」
        vehicle.setCurrentOdometerKm(7000);

        VehicleMaintenanceSummaryResponse summary = service.summary(vehicle, Double.NaN);

        assertEquals(VehicleMaintenanceSummaryResponse.UNKNOWN, summary.getDecision());
        assertTrue(summary.getReasons().contains("這趟的預估里程無效，無法預估保養"));
        assertDoesNotThrow(() -> service.assertCanDispatch(vehicle, -1.0));
    }

    @Test
    void 基準只填一半_快到了照樣提醒_會超過照樣擋() {
        // 知道上次小保、不知道上次大保：大保算不出來，小保照算
        vehicle.setLastMajorMaintenanceKm(null);

        VehicleMaintenanceSummaryResponse summary = service.summary(vehicle, 0.0);
        assertEquals(VehicleMaintenanceSummaryResponse.WARNING, summary.getDecision());
        assertTrue(summary.getReasons().contains("還沒有大保基準"), summary.getReasons().toString());
        assertTrue(summary.getReasons().contains("距離小保剩 0.0 km"), summary.getReasons().toString());

        assertEquals(VehicleMaintenanceSummaryResponse.BLOCKED, service.summary(vehicle, 20.0).getDecision());
    }

    @Test
    void 送修中或已退役一律擋() {
        for (VehicleStatus status : List.of(VehicleStatus.RETIRED, VehicleStatus.MAINTENANCE,
                VehicleStatus.MINOR_MAINTENANCE, VehicleStatus.MAJOR_MAINTENANCE)) {
            vehicle.setStatus(status);
            assertEquals(VehicleMaintenanceSummaryResponse.BLOCKED, service.summary(vehicle, 0.0).getDecision(), status.name());
            assertThrows(IllegalArgumentException.class, () -> service.assertCanDispatch(vehicle, 0.0));
        }
    }

    @Test
    void 沒有要評估的趟次時_預估欄位照現況算_plannedKm維持null() {
        VehicleMaintenanceSummaryResponse summary = service.summary(vehicle, null);

        assertNull(summary.getPlannedKm());
        assertEquals(18000.0, summary.getProjectedMajorKm());
        assertEquals(492000, summary.getRetirementRemainingKm());
    }

    // ── 送修、完成、取消 ──────────────────────────────────

    @Test
    void 送小保建一筆進行中紀錄_重複儲存不會多建() {
        service.changeStatus(vehicle, VehicleStatus.MINOR_MAINTENANCE);
        service.changeStatus(vehicle, VehicleStatus.MINOR_MAINTENANCE);

        ArgumentCaptor<VehicleMaintenanceRecordsEntity> saved = ArgumentCaptor.forClass(VehicleMaintenanceRecordsEntity.class);
        verify(recordsDAO, times(1)).save(saved.capture());
        assertEquals(MaintenanceRecordType.MINOR, saved.getValue().getType());
        assertEquals(MaintenanceRecordStatus.ACTIVE, saved.getValue().getStatus());
        assertEquals(8000, saved.getValue().getSentOdometerKm());
        assertEquals(VEHICLE_ID, saved.getValue().getActiveVehicleId());
        assertEquals(VehicleStatus.MINOR_MAINTENANCE, vehicle.getStatus());
    }

    @Test
    void 小保完成_小保基準改成當下里程_大保不動() {
        givenActive(MaintenanceRecordType.MINOR, VehicleStatus.MINOR_MAINTENANCE);

        service.changeStatus(vehicle, VehicleStatus.AVAILABLE);

        assertEquals(8000, vehicle.getLastMinorMaintenanceKm());
        assertEquals(6000, vehicle.getLastMajorMaintenanceKm());
        assertEquals(VehicleStatus.AVAILABLE, vehicle.getStatus());
    }

    @Test
    void 大保完成_只重設大保_退役不重設() {
        VehicleMaintenanceRecordsEntity active = givenActive(MaintenanceRecordType.MAJOR, VehicleStatus.MAJOR_MAINTENANCE);

        service.changeStatus(vehicle, VehicleStatus.AVAILABLE);

        assertEquals(5000, vehicle.getLastMinorMaintenanceKm());
        assertEquals(8000, vehicle.getLastMajorMaintenanceKm());
        assertEquals(MaintenanceRecordStatus.COMPLETED, active.getStatus());
        assertEquals(8000, active.getCompletedOdometerKm());
        assertNotNull(active.getCompletedAt());
        assertNull(active.getActiveVehicleId());
        assertEquals(492000, service.summary(vehicle, null).getRetirementRemainingKm());
    }

    @Test
    void 維修可以沒有里程_完成時兩個基準都不動() {
        vehicle.setCurrentOdometerKm(null);
        service.changeStatus(vehicle, VehicleStatus.MAINTENANCE);
        ArgumentCaptor<VehicleMaintenanceRecordsEntity> saved = ArgumentCaptor.forClass(VehicleMaintenanceRecordsEntity.class);
        verify(recordsDAO).save(saved.capture());
        assertEquals(MaintenanceRecordType.REPAIR, saved.getValue().getType());
        assertNull(saved.getValue().getSentOdometerKm());

        vehicle.setCurrentOdometerKm(8000);
        givenActive(MaintenanceRecordType.REPAIR, VehicleStatus.MAINTENANCE);
        service.changeStatus(vehicle, VehicleStatus.AVAILABLE);

        assertEquals(5000, vehicle.getLastMinorMaintenanceKm());
        assertEquals(6000, vehicle.getLastMajorMaintenanceKm());
    }

    @Test
    void 沒有行車紀錄器里程不能送保養() {
        vehicle.setCurrentOdometerKm(null);

        assertThrows(IllegalArgumentException.class, () -> service.changeStatus(vehicle, VehicleStatus.MINOR_MAINTENANCE));
        verify(recordsDAO, never()).save(any());
    }

    @Test
    void 有進行中的送修_不能再送_也不能直接改成退役() {
        givenActive(MaintenanceRecordType.MINOR, VehicleStatus.MINOR_MAINTENANCE);

        assertThrows(IllegalArgumentException.class, () -> service.changeStatus(vehicle, VehicleStatus.MAJOR_MAINTENANCE));
        assertThrows(IllegalArgumentException.class, () -> service.changeStatus(vehicle, VehicleStatus.RETIRED));
    }

    @Test
    void 還沒收車不能改保養狀態() {
        when(mileageLogsDAO.findOpenByVehicleForUpdate(VEHICLE_ID)).thenReturn(List.of(new MileageLogsEntity()));

        assertThrows(IllegalArgumentException.class, () -> service.changeStatus(vehicle, VehicleStatus.MINOR_MAINTENANCE));
    }

    @Test
    void 完成的紀錄算進次數_最近一次的時間取第一筆() {
        VehicleMaintenanceRecordsEntity repair = new VehicleMaintenanceRecordsEntity();
        repair.setType(MaintenanceRecordType.REPAIR);
        repair.setStatus(MaintenanceRecordStatus.COMPLETED);
        VehicleMaintenanceRecordsEntity cancelled = new VehicleMaintenanceRecordsEntity();
        cancelled.setType(MaintenanceRecordType.MINOR);
        cancelled.setStatus(MaintenanceRecordStatus.CANCELLED);
        when(recordsDAO.findAllByVehicleIdOrderByIdDesc(VEHICLE_ID)).thenReturn(List.of(repair, cancelled));

        VehicleMaintenanceSummaryResponse summary = service.summary(vehicle, null);

        assertEquals(1, summary.getRepairCount());
        // 取消的不算
        assertEquals(0, summary.getMinorCount());
    }

    private void assertUnknownButDispatchable(String reason) {
        VehicleMaintenanceSummaryResponse summary = service.summary(vehicle, 20.0);
        assertEquals(VehicleMaintenanceSummaryResponse.UNKNOWN, summary.getDecision(), reason);
        assertTrue(summary.getReasons().contains(reason), summary.getReasons().toString());
        assertDoesNotThrow(() -> service.assertCanDispatch(vehicle, 20.0));
    }

    private VehicleMaintenanceRecordsEntity givenActive(MaintenanceRecordType type, VehicleStatus status) {
        VehicleMaintenanceRecordsEntity active = new VehicleMaintenanceRecordsEntity();
        active.setVehicleId(VEHICLE_ID);
        active.setType(type);
        active.setStatus(MaintenanceRecordStatus.ACTIVE);
        active.setActiveVehicleId(VEHICLE_ID);
        when(recordsDAO.findByActiveVehicleId(VEHICLE_ID)).thenReturn(Optional.of(active));
        vehicle.setStatus(status);
        return active;
    }
}
