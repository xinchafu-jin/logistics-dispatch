package com.example.backend.service;

import com.example.backend.constants.RouteStatus;
import com.example.backend.dao.DriversDAO;
import com.example.backend.dao.EmergencyLeaveRequestsDAO;
import com.example.backend.dao.MileageLogsDAO;
import com.example.backend.dao.OrdersDAO;
import com.example.backend.dao.RoutesDAO;
import com.example.backend.dao.VehiclesDAO;
import com.example.backend.dto.request.MileageRequestDTO;
import com.example.backend.dto.respones.MileageLogResponse;
import com.example.backend.entity.DriversEntity;
import com.example.backend.entity.MileageLogsEntity;
import com.example.backend.entity.RoutesEntity;
import com.example.backend.entity.VehiclesEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 出車讀數跟系統記錄的車輛里程不同時照樣出車，而且收車不會因此被擋。
 *
 * <p>DAO 全部用 mock。司機 7 號今天有一條已發布的路線 30，開車 5；系統記錄這台車上次收車在 18400 km。</p>
 */
class MileageLogsServiceOdometerTest {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final long DRIVER_ID = 7L;
    private static final long ROUTE_ID = 30L;
    private static final long VEHICLE_ID = 5L;

    private VehiclesDAO vehiclesDAO;
    private VehiclesEntity vehicle;
    // 今天存下的里程紀錄：出車前是 null，收車時 findForUpdate 要找得到它
    private MileageLogsEntity savedMileage;
    private MileageLogsService service;

    @BeforeEach
    void setUp() {
        MileageLogsDAO mileageLogsDAO = mock(MileageLogsDAO.class);
        DriversDAO driversDAO = mock(DriversDAO.class);
        RoutesDAO routesDAO = mock(RoutesDAO.class);
        AttendanceService attendanceService = mock(AttendanceService.class);
        vehiclesDAO = mock(VehiclesDAO.class);

        DriversEntity driver = new DriversEntity();
        driver.setId(DRIVER_ID);
        driver.setIsActive(true);
        when(driversDAO.findById(DRIVER_ID)).thenReturn(Optional.of(driver));

        RoutesEntity route = new RoutesEntity();
        route.setId(ROUTE_ID);
        route.setDate(LocalDate.now(TAIPEI));
        route.setDriverId(DRIVER_ID);
        route.setVehicleId(VEHICLE_ID);
        route.setStatus(RouteStatus.PUBLISHED);
        when(routesDAO.findByDateAndDriverIdAndStatusOrderByIdAsc(
                any(LocalDate.class), eq(DRIVER_ID), eq(RouteStatus.PUBLISHED))).thenReturn(List.of(route));
        when(attendanceService.isGpsUploadAllowed(DRIVER_ID)).thenReturn(true);

        vehicle = new VehiclesEntity();
        vehicle.setId(VEHICLE_ID);
        vehicle.setCurrentOdometerKm(18400);
        when(vehiclesDAO.findByIdForUpdate(VEHICLE_ID)).thenReturn(Optional.of(vehicle));
        when(vehiclesDAO.findById(VEHICLE_ID)).thenReturn(Optional.of(vehicle));
        // 照 VehiclesDAO.updateCurrentOdometer 的 JPQL：車輛讀數等於出車讀數（或還沒有讀數）才更新
        when(vehiclesDAO.updateCurrentOdometer(eq(VEHICLE_ID), anyInt(), anyInt())).thenAnswer(invocation -> {
            int startOdometer = invocation.getArgument(1);
            int endOdometer = invocation.getArgument(2);
            if (vehicle.getCurrentOdometerKm() != null && vehicle.getCurrentOdometerKm() != startOdometer) {
                return 0;
            }
            vehicle.setCurrentOdometerKm(endOdometer);
            return 1;
        });

        when(mileageLogsDAO.findForUpdate(eq(DRIVER_ID), any(LocalDate.class)))
                .thenAnswer(invocation -> Optional.ofNullable(savedMileage));
        when(mileageLogsDAO.save(any(MileageLogsEntity.class))).thenAnswer(invocation -> {
            savedMileage = invocation.getArgument(0);
            return savedMileage;
        });

        // 倉庫範圍、路段里程、GPS 結算、臨時請假交接各有自己的測試範圍，這裡只要不出錯就好
        service = new MileageLogsService(mileageLogsDAO, driversDAO, routesDAO, mock(OrdersDAO.class), vehiclesDAO,
                attendanceService, mock(VehicleMileageSettlementService.class), mock(EmergencyLeaveRequestsDAO.class),
                mock(EmergencyLeaveService.class), mock(WarehouseProximityService.class),
                mock(RouteLegMileageService.class), mock(MileagePhotoStorageService.class));
    }

    @Test
    void readingDifferentFromVehicleIsRecordedAndWrittenBack() {
        // 上次收車 18400，這次儀表板是 18450：中間在系統外開了 50 km
        MileageLogResponse response = service.start(DRIVER_ID, odometer(18450));

        assertEquals(18450, response.getStartOdometer());
        assertEquals(18450, vehicle.getCurrentOdometerKm());
        verify(vehiclesDAO).save(vehicle);
    }

    @Test
    void readingLowerThanVehicleIsAlsoAccepted() {
        // 讀數倒退多半是打錯，但出車一律不擋，交給出車照片和報表事後查
        MileageLogResponse response = service.start(DRIVER_ID, odometer(18390));

        assertEquals(18390, response.getStartOdometer());
        assertEquals(18390, vehicle.getCurrentOdometerKm());
    }

    @Test
    void firstReadingForVehicleWithoutOdometer() {
        vehicle.setCurrentOdometerKm(null);

        service.start(DRIVER_ID, odometer(18400));

        assertEquals(18400, vehicle.getCurrentOdometerKm());
    }

    @Test
    void blankReadingIsRejectedWithoutTouchingVehicle() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.start(DRIVER_ID, odometer(null)));

        assertEquals("出車總里程不能留空", error.getMessage());
        assertEquals(18400, vehicle.getCurrentOdometerKm());
        verify(vehiclesDAO, never()).save(any());
    }

    /** 只拿掉出車檢查、沒把讀數寫回車輛的話，收車會丟出「車輛總里程已變更」。 */
    @Test
    void endAfterOffSystemDrivingIsNotBlocked() {
        service.start(DRIVER_ID, odometer(18450));

        MileageLogResponse response = service.end(DRIVER_ID, odometer(18480));

        assertEquals(30, response.getActualDistance());
        assertEquals(18480, vehicle.getCurrentOdometerKm());
    }

    private static MileageRequestDTO odometer(Integer value) {
        MileageRequestDTO request = new MileageRequestDTO();
        request.setOdometer(value);
        return request;
    }
}
