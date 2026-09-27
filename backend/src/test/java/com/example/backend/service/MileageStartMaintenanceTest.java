package com.example.backend.service;

import com.example.backend.constants.RouteStatus;
import com.example.backend.dao.*;
import com.example.backend.dto.request.MileageRequestDTO;
import com.example.backend.entity.DriversEntity;
import com.example.backend.entity.RoutesEntity;
import com.example.backend.entity.VehiclesEntity;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MileageStartMaintenanceTest {
    @Test void maintenanceBlockPreventsDriverMileageStart() {
        MileageLogsDAO mileage = mock(MileageLogsDAO.class);
        DriversDAO drivers = mock(DriversDAO.class);
        RoutesDAO routes = mock(RoutesDAO.class);
        VehiclesDAO vehicles = mock(VehiclesDAO.class);
        AttendanceService attendance = mock(AttendanceService.class);
        DispatchVehicleMaintenanceGuard guard = mock(DispatchVehicleMaintenanceGuard.class);
        MileageLogsService service = new MileageLogsService(mileage, drivers, routes,
                mock(OrdersDAO.class), vehicles, attendance, mock(VehicleMileageSettlementService.class),
                mock(EmergencyLeaveRequestsDAO.class), mock(EmergencyLeaveService.class),
                mock(WarehouseProximityService.class), mock(RouteLegMileageService.class),
                mock(MileagePhotoStorageService.class), guard, mock(PreTripInspectionService.class));
        DriversEntity driver = new DriversEntity(); driver.setIsActive(true);
        RoutesEntity route = new RoutesEntity(); route.setId(10L); route.setVehicleId(20L);
        VehiclesEntity vehicle = new VehiclesEntity(); vehicle.setId(20L); vehicle.setCurrentOdometerKm(8000);
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Taipei"));
        when(drivers.findById(1L)).thenReturn(Optional.of(driver));
        when(routes.findByDateAndDriverIdAndStatusOrderByIdAsc(today, 1L, RouteStatus.PUBLISHED))
                .thenReturn(List.of(route));
        when(attendance.isGpsUploadAllowed(1L)).thenReturn(true);
        when(mileage.findOpenByVehicleForUpdate(20L)).thenReturn(List.of());
        when(vehicles.findByIdForUpdate(20L)).thenReturn(Optional.of(vehicle));
        doThrow(new IllegalArgumentException("禁止出車"))
                .when(guard).assertCanStart(route, vehicle);
        MileageRequestDTO request = new MileageRequestDTO(); request.setOdometer(8000);

        assertThrows(IllegalArgumentException.class, () -> service.start(1L, request));
        verify(guard).assertCanStart(route, vehicle);
        verify(mileage, never()).save(any());
        verify(vehicles, never()).save(any());
    }
}
