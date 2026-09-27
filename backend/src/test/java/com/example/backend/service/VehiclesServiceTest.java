package com.example.backend.service;

import com.example.backend.constants.VehicleStatus;
import com.example.backend.dao.VehiclesDAO;
import com.example.backend.dto.request.VehiclesDTO;
import com.example.backend.entity.VehiclesEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class VehiclesServiceTest {
    private VehiclesDAO vehicles;
    private VehicleMaintenanceService maintenance;
    private DispatchBoardPushService push;
    private VehiclesService service;

    @BeforeEach void setup() {
        vehicles = mock(VehiclesDAO.class);
        maintenance = mock(VehicleMaintenanceService.class);
        push = mock(DispatchBoardPushService.class);
        service = new VehiclesService(vehicles, maintenance, push);
        when(vehicles.save(any())).thenAnswer(invocation -> {
            VehiclesEntity entity = invocation.getArgument(0);
            if (entity.getId() == null) entity.setId(1L);
            return entity;
        });
        when(maintenance.mileageSnapshot(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private VehiclesDTO dto(Integer current, VehicleStatus status) {
        VehiclesDTO dto = new VehiclesDTO();
        dto.setPlateNumber("TEST-1");
        dto.setWarehouseId(1L);
        dto.setCapacity(20);
        dto.setStatus(status);
        dto.setCurrentOdometerKm(current);
        return dto;
    }

    @Test void newZeroKmVehicleStartsWithZeroBaselines() {
        VehiclesDTO created = service.create(dto(0, VehicleStatus.AVAILABLE));
        assertEquals(0, created.getCurrentOdometerKm());
        assertEquals(0, created.getLastMinorMaintenanceKm());
        assertEquals(0, created.getLastMajorMaintenanceKm());
        verify(push).markResourcesChanged();
    }

    @Test void olderVehicleWithUnknownHistoryDoesNotGetInventedBaselines() {
        VehiclesDTO created = service.create(dto(8000, VehicleStatus.AVAILABLE));
        assertEquals(8000, created.getCurrentOdometerKm());
        assertNull(created.getLastMinorMaintenanceKm());
        assertNull(created.getLastMajorMaintenanceKm());
    }

    @Test void initialBaselineCannotExceedActualOdometer() {
        VehiclesDTO requested = dto(100, VehicleStatus.AVAILABLE);
        requested.setLastMinorMaintenanceKm(101);
        assertThrows(IllegalArgumentException.class, () -> service.create(requested));
        verify(vehicles, never()).save(any());
    }

    @Test void editingVehicleCannotOverwriteActualMileage() {
        VehiclesEntity stored = new VehiclesEntity();
        stored.setId(1L); stored.setPlateNumber("TEST-1");
        stored.setCurrentOdometerKm(8000);
        when(vehicles.findByIdForUpdate(1L)).thenReturn(Optional.of(stored));

        assertThrows(IllegalArgumentException.class, () -> service.update(1L, dto(8050, VehicleStatus.AVAILABLE)));
        verify(vehicles, never()).save(any());
        verify(push, never()).markResourcesChanged();
    }

    @Test void statusTransitionUsesMaintenanceRecordWorkflow() {
        VehiclesEntity stored = new VehiclesEntity();
        stored.setId(1L); stored.setPlateNumber("TEST-1");
        stored.setCurrentOdometerKm(8000);
        when(vehicles.findByIdForUpdate(1L)).thenReturn(Optional.of(stored));

        service.update(1L, dto(8000, VehicleStatus.MINOR_MAINTENANCE));
        verify(maintenance).changeStatus(stored, VehicleStatus.MINOR_MAINTENANCE);
        verify(push).markResourcesChanged();
    }

    @Test void vehicleWithRepairHistoryCannotBeDeleted() {
        VehiclesEntity stored = new VehiclesEntity();
        stored.setId(1L);
        when(vehicles.findByIdForUpdate(1L)).thenReturn(Optional.of(stored));
        when(maintenance.hasHistory(1L)).thenReturn(true);

        assertThrows(IllegalArgumentException.class, () -> service.delete(1L));
        verify(vehicles, never()).delete(any());
    }
}
