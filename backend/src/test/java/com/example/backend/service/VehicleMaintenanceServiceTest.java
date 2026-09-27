package com.example.backend.service;

import com.example.backend.constants.VehicleStatus;
import com.example.backend.dao.*;
import com.example.backend.dto.request.VehicleMaintenanceRulesDTO;
import com.example.backend.entity.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VehicleMaintenanceServiceTest {
    private VehiclesEntity vehicle;
    private VehicleMaintenancePolicy policy;
    private VehicleMaintenanceService service;
    private VehicleMaintenancePolicyDAO policies;
    private VehicleMaintenanceRecordDAO records;
    private MileageLogsDAO mileage;
    private DispatchBoardPushService push;

    @BeforeEach void setup() {
        vehicle = new VehiclesEntity(); vehicle.setId(1L); vehicle.setPlateNumber("TEST");
        vehicle.setTonnage(new BigDecimal("3.50")); vehicle.setCurrentOdometerKm(8000);
        vehicle.setLastMinorMaintenanceKm(5000); vehicle.setLastMajorMaintenanceKm(6000);
        policy = new VehicleMaintenancePolicy(); policy.tonnage = vehicle.getTonnage();
        policy.minorIntervalKm = 3000; policy.majorIntervalKm = 20000; policy.retirementKm = 500000;
        policies = mock(VehicleMaintenancePolicyDAO.class); records = mock(VehicleMaintenanceRecordDAO.class);
        mileage = mock(MileageLogsDAO.class);
        push = mock(DispatchBoardPushService.class);
        var settings = mock(VehicleMaintenanceSettingsDAO.class);
        when(settings.findById(1L)).thenReturn(Optional.of(new VehicleMaintenanceSettings()));
        when(policies.findById(policy.tonnage)).thenAnswer(i -> Optional.of(policy));
        when(records.findByVehicleIdOrderByIdDesc(1L)).thenReturn(List.of());
        service = new VehicleMaintenanceService(policies, settings, records, mileage, mock(VehiclesDAO.class), push);
    }

    private com.example.backend.dto.respones.VehicleMaintenanceSummary assess(double planned) {
        return VehicleMaintenanceService.assess(vehicle, policy, 500, planned, 0, 0, 0, null, null, null);
    }

    @Test void exactZeroIsWarningNotOverdue() {
        assertEquals("WARNING", assess(0).decision());
        assertEquals(0.0, assess(0).projectedMinorKm());
    }
    @Test void realMileageConsumesRemainingWithoutService() {
        vehicle.setCurrentOdometerKm(7500); assertEquals(500, assess(0).minorRemainingKm());
        vehicle.setCurrentOdometerKm(7550); assertEquals(450, assess(0).minorRemainingKm());
        vehicle.setCurrentOdometerKm(7990);
        assertEquals("BLOCKED", assess(20).decision());
        assertEquals(-10.0, assess(20).projectedMinorKm());
    }
    @Test void moreThanWarningIsNormalAndWarningIsConfigurable() {
        vehicle.setCurrentOdometerKm(7400);
        assertEquals("NORMAL", assess(0).decision());
        assertEquals("WARNING", VehicleMaintenanceService.assess(vehicle, policy, 700, 0.0, 0, 0, 0, null, null, null).decision());
    }
    @Test void anyOneOfThreeLimitsBlocks() {
        policy.minorIntervalKm = 40000;
        policy.majorIntervalKm = 2010; assertEquals("BLOCKED", assess(20).decision());
        policy.majorIntervalKm = 40000; policy.retirementKm = 8010;
        assertEquals("BLOCKED", assess(20).decision());
        assertEquals(-10.0, assess(20).projectedRetirementKm());
    }
    @Test void changingIntervalKeepsLastServiceBaseline() {
        policy.minorIntervalKm = 4000;
        assertEquals(1000, assess(0).minorRemainingKm());
        assertEquals(5000, vehicle.getLastMinorMaintenanceKm());
    }
    @Test void osrmNeverChangesOdometerAndGpsIsNotAnOdometer() {
        vehicle.setCumulativeMileageKm(999999.0);
        assess(20);
        assertEquals(8000, vehicle.getCurrentOdometerKm());
        assertEquals(5000, vehicle.getLastMinorMaintenanceKm());
    }
    @Test void missingBaselineAndInvalidEstimateCannotPass() {
        vehicle.setLastMinorMaintenanceKm(null);
        assertEquals("UNKNOWN", assess(0).decision());
        vehicle.setLastMinorMaintenanceKm(5000);
        assertEquals("UNKNOWN", assess(Double.NaN).decision());
        assertEquals("UNKNOWN", assess(-1).decision());
    }
    @Test void retiredRepairsAndBothServiceStatusesBlock() {
        for (var status : List.of(VehicleStatus.RETIRED, VehicleStatus.MAINTENANCE, VehicleStatus.MINOR_MAINTENANCE, VehicleStatus.MAJOR_MAINTENANCE)) {
            vehicle.setStatus(status); assertEquals("BLOCKED", assess(0).decision());
        }
    }
    @Test void repairAndReturnToAvailableNeverCreateRoutineServiceOrResetMileage() {
        service.changeStatus(vehicle, VehicleStatus.MAINTENANCE);
        service.changeStatus(vehicle, VehicleStatus.MAINTENANCE);
        var captured = org.mockito.ArgumentCaptor.forClass(VehicleMaintenanceRecord.class);
        verify(records, times(1)).save(captured.capture());
        var repair = captured.getValue();
        assertEquals("REPAIR", repair.type); assertEquals("ACTIVE", repair.status);
        assertNotNull(repair.sentAt); assertEquals(8000, repair.sentOdometerKm);
        when(records.findByActiveVehicleId(1L)).thenReturn(Optional.of(repair));
        assertEquals(VehicleStatus.MAINTENANCE, vehicle.getStatus());
        assertEquals("BLOCKED", assess(0).decision());
        service.changeStatus(vehicle, VehicleStatus.AVAILABLE);
        service.changeStatus(vehicle, VehicleStatus.AVAILABLE);
        assertEquals(VehicleStatus.AVAILABLE, vehicle.getStatus());
        assertEquals(8000, vehicle.getCurrentOdometerKm());
        assertEquals(5000, vehicle.getLastMinorMaintenanceKm());
        assertEquals(6000, vehicle.getLastMajorMaintenanceKm());
        assertEquals(0, assess(0).minorRemainingKm());
        assertEquals(18000, assess(0).majorRemainingKm());
        assertEquals("COMPLETED", repair.status); assertNotNull(repair.completedAt);
        assertEquals(8000, repair.completedOdometerKm); assertNull(repair.activeVehicleId);
        when(records.findByVehicleIdOrderByIdDesc(1L)).thenReturn(List.of(repair));
        var result = service.summary(vehicle, null);
        assertEquals(1, result.repairCount()); assertEquals(repair.completedAt, result.lastRepairAt());
        assertEquals(0, result.minorCount()); assertEquals(0, result.majorCount());
        verify(records, times(2)).save(repair);
    }
    @Test void repairCanBeRecordedWithoutInventingMissingMileage() {
        vehicle.setCurrentOdometerKm(null);
        service.changeStatus(vehicle, VehicleStatus.MAINTENANCE);
        var captured = org.mockito.ArgumentCaptor.forClass(VehicleMaintenanceRecord.class);
        verify(records).save(captured.capture());
        assertEquals("REPAIR", captured.getValue().type); assertNull(captured.getValue().sentOdometerKm);
    }
    @Test void legacyRepairCompletionKeepsUnknownStartAndBothServiceBaselines() {
        VehicleMaintenanceRecord active = new VehicleMaintenanceRecord();
        active.type = "REPAIR"; active.status = "ACTIVE"; active.activeVehicleId = 1L;
        when(records.findByActiveVehicleId(1L)).thenReturn(Optional.of(active));
        vehicle.setStatus(VehicleStatus.MAINTENANCE);
        service.changeStatus(vehicle, VehicleStatus.AVAILABLE);
        assertNull(active.sentAt); assertNull(active.sentOdometerKm);
        assertNotNull(active.completedAt); assertEquals("COMPLETED", active.status);
        assertEquals(5000, vehicle.getLastMinorMaintenanceKm()); assertEquals(6000, vehicle.getLastMajorMaintenanceKm());
    }
    @Test void repeatedSaveOfSameServiceDoesNotDuplicateRecord() {
        service.changeStatus(vehicle, VehicleStatus.MINOR_MAINTENANCE);
        service.changeStatus(vehicle, VehicleStatus.MINOR_MAINTENANCE);
        verify(records, times(1)).save(any(VehicleMaintenanceRecord.class));
        assertEquals(5000, vehicle.getLastMinorMaintenanceKm());
    }
    @Test void completedMinorOnlyResetsMinorAndKeepsTimestampAndMileage() {
        VehicleMaintenanceRecord active = new VehicleMaintenanceRecord(); active.type = "MINOR"; active.status = "ACTIVE"; active.activeVehicleId = 1L;
        when(records.findByActiveVehicleId(1L)).thenReturn(Optional.of(active));
        vehicle.setStatus(VehicleStatus.MINOR_MAINTENANCE);
        service.changeStatus(vehicle, VehicleStatus.AVAILABLE);
        assertEquals(8000, vehicle.getLastMinorMaintenanceKm());
        assertEquals(6000, vehicle.getLastMajorMaintenanceKm());
        assertEquals("COMPLETED", active.status); assertEquals(8000, active.completedOdometerKm);
        assertNotNull(active.completedAt); assertNull(active.activeVehicleId);
    }
    @Test void completedMajorOnlyResetsMajorAndDoesNotResetRetirement() {
        VehicleMaintenanceRecord active = new VehicleMaintenanceRecord(); active.type = "MAJOR"; active.status = "ACTIVE"; active.activeVehicleId = 1L;
        when(records.findByActiveVehicleId(1L)).thenReturn(Optional.of(active));
        vehicle.setStatus(VehicleStatus.MAJOR_MAINTENANCE);
        service.changeStatus(vehicle, VehicleStatus.AVAILABLE);
        assertEquals(5000, vehicle.getLastMinorMaintenanceKm()); assertEquals(8000, vehicle.getLastMajorMaintenanceKm());
        assertEquals(492000, assess(0).retirementRemainingKm());
    }
    @Test void allVehiclesOfSameTonnageReadUpdatedPolicy() {
        VehiclesEntity other = new VehiclesEntity(); other.setTonnage(vehicle.getTonnage()); other.setCurrentOdometerKm(7000);
        other.setLastMinorMaintenanceKm(5000); other.setLastMajorMaintenanceKm(6000);
        assertEquals(0, service.summary(vehicle, null).minorRemainingKm());
        when(policies.save(any())).thenAnswer(invocation -> { policy = invocation.getArgument(0); return policy; });
        service.saveRules(new VehicleMaintenanceRulesDTO(500, List.of(new VehicleMaintenanceRulesDTO.Rule(new BigDecimal("3.50"), 4000, 20000, 500000))));
        assertEquals(1000, service.summary(vehicle, null).minorRemainingKm());
        assertEquals(2000, service.summary(other, null).minorRemainingKm());
        verify(push).markResourcesChanged();
    }

    @Test void completedTripRestoresOdometerWithoutUsingGpsDistance() {
        vehicle.setCurrentOdometerKm(null);
        MileageLogsEntity trip = new MileageLogsEntity();
        trip.setEndOdometer(8100);
        trip.setGpsDistanceKm(999999.0);
        when(mileage.findLatestCompletedVehicleMileage(eq(1L), any())).thenReturn(List.of(trip));

        VehiclesEntity snapshot = service.mileageSnapshot(vehicle);
        assertEquals(8100, snapshot.getCurrentOdometerKm());
        assertNull(vehicle.getCurrentOdometerKm());
        assertEquals(-100, service.summary(vehicle, null).minorRemainingKm());
    }

    @Test void legacyCompletedRecordWithoutTimeDoesNotCrashSummary() {
        VehicleMaintenanceRecord legacy = new VehicleMaintenanceRecord();
        legacy.type = "REPAIR"; legacy.status = "COMPLETED";
        when(records.findByVehicleIdOrderByIdDesc(1L)).thenReturn(List.of(legacy));

        var result = service.summary(vehicle, null);
        assertEquals(1, result.repairCount());
        assertNull(result.lastRepairAt());
    }
}
