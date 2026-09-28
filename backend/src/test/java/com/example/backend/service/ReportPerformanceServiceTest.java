package com.example.backend.service;

import com.example.backend.constants.ShiftType;
import com.example.backend.dao.*;
import com.example.backend.dto.respones.ReportPerformanceResponse.*;
import com.example.backend.dto.respones.ReportResponses;
import com.example.backend.entity.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReportPerformanceServiceTest {
    private final ReportService reports = mock(ReportService.class);
    private final ReportReadDAO reads = mock(ReportReadDAO.class);
    private final DriversDAO drivers = mock(DriversDAO.class);
    private final VehiclesDAO vehicles = mock(VehiclesDAO.class);
    private final WarehousesDAO warehouses = mock(WarehousesDAO.class);
    private final ReportPerformanceService service = new ReportPerformanceService(reports, reads, drivers, vehicles, warehouses);
    private final LocalDate day = LocalDate.now(ZoneId.of("Asia/Taipei")).minusDays(2);
    private final ReportService.Range range = new ReportService.Range(day, day.plusDays(4));
    private List<ReportResponses.AttendanceRow> shifts;

    @BeforeEach void setup() {
        DriversEntity one = new DriversEntity(); one.setId(1L); one.setWarehouseId(1L);
        DriversEntity two = new DriversEntity(); two.setId(2L); two.setWarehouseId(2L);
        when(drivers.findAll()).thenReturn(List.of(one, two));
        var first = new WarehousesEntity(); first.setId(1L); first.setName("左營倉");
        var second = new WarehousesEntity(); second.setId(2L); second.setName("其他倉");
        var empty = new WarehousesEntity(); empty.setId(3L); empty.setName("無紀錄倉");
        when(warehouses.findAll()).thenReturn(List.of(first, second, empty));
        VehiclesEntity vehicle = new VehiclesEntity(); vehicle.setId(10L); vehicle.setPlateNumber("TEST");
        when(vehicles.findAll()).thenReturn(List.of(vehicle));
        shifts = new ArrayList<>();
        when(reports.attendance(eq(range), any())).thenAnswer(invocation -> {
            Long driverId = invocation.getArgument(1);
            var response = new ReportResponses.Attendance();
            response.setShifts(shifts.stream().filter(r -> driverId == null || driverId.equals(r.getDriverId())).toList());
            return response;
        });
    }
    private ReportResponses.AttendanceRow shift(long id, long driver, LocalDate date, LocalTime in, LocalTime out) {
        var row = new ReportResponses.AttendanceRow(); row.setShiftId(id); row.setDriverId(driver);
        row.setDriverName("司機" + driver); row.setWorkDate(date); row.setShiftType(ShiftType.WORK);
        row.setScheduledStartAt(date.atTime(8, 0)); row.setScheduledEndAt(date.atTime(17, 0));
        row.setClockInAt(in == null ? null : date.atTime(in)); row.setClockOutAt(out == null ? null : date.atTime(out));
        shifts.add(row); return row;
    }
    @Test void attendanceUsesDueWorkShiftsAndOvertimeUsesOnlyFinishedPunches() {
        shift(1, 1, day, LocalTime.of(8, 0), LocalTime.of(17, 30));
        shift(2, 1, day, LocalTime.of(8, 0, 1), LocalTime.of(16, 50)); // one second late is not rounded to on-time
        shift(3, 2, day, null, null);
        shift(4, 2, day, LocalTime.of(7, 59), null); // unfinished does not lower overtime rate
        shift(5, 2, day.plusDays(4), null, null); // future is not absent
        shift(6, 1, day, null, null).setShiftType(ShiftType.DAY_OFF);
        var result = service.performance(range, null, null, null);
        Workforce w = result.workforce();
        assertEquals(5, w.scheduledWorkShifts()); assertEquals(4, w.dueShifts());
        assertEquals(3, w.attendedShifts()); assertEquals(2, w.onTimeShifts());
        assertEquals(1, w.lateShifts()); assertEquals(1, w.missingClockInShifts());
        assertEquals(75, w.attendanceRate()); assertEquals(200.0 / 3, w.onTimeRate(), 0.000001);
        assertEquals(2, w.finishedShifts()); assertEquals(50, w.overtimeRate()); assertEquals(30, w.overtimeMinutes());
        assertEquals(3, result.warehouses().size());
        assertNull(result.warehouses().get(2).workforce().attendanceRate());
        assertEquals(0, result.warehouses().get(2).fleet().startedTrips());
    }
    @Test void approvedFullDayAndStartOfDayPartialLeavesDoNotChangeRosterOrFalselyCountAbsence() {
        var fullRow = shift(1, 1, day, null, null);
        var partialRow = shift(2, 1, day, LocalTime.of(10, 0), LocalTime.of(17, 10));
        var full = new DriverLeaveRequestsEntity(); full.setDriverShiftId(1L); full.setFullDay(true);
        var partial = new DriverLeaveRequestsEntity(); partial.setDriverShiftId(2L); partial.setFullDay(false);
        partial.setLeaveStart(LocalTime.of(8, 0)); partial.setLeaveEnd(LocalTime.of(10, 0));
        when(reads.approvedLeaves(range.getFrom(), range.getTo())).thenReturn(List.of(full, partial));
        var result = service.performance(range, null, null, null);
        assertEquals(1, result.workforce().excusedFullDayShifts());
        assertEquals(1, result.workforce().dueShifts()); assertEquals(100, result.workforce().onTimeRate());
        assertEquals(day.atTime(10, 0), result.shifts().get(1).expectedStartAt());
        assertEquals(day.atTime(8, 0), partialRow.getScheduledStartAt()); assertEquals(ShiftType.WORK, fullRow.getShiftType());
    }
    @Test void overtimeExcludesBreakAfterScheduledEndAndStartsNoEarlierThanActualArrival() {
        var row = shift(1, 1, day, LocalTime.of(8, 0), LocalTime.of(18, 0));
        row.setBreakStartedAt(day.atTime(17, 10)); row.setBreakExpectedEndAt(day.atTime(17, 30));
        shift(2, 1, day, LocalTime.of(17, 45), LocalTime.of(18, 0));
        var result = service.performance(range, null, null, null);
        assertEquals(55, result.workforce().overtimeMinutes());
        assertEquals(100, result.workforce().overtimeRate());
    }
    @Test void warehouseFilteringKeepsAttendanceAndTripSourcesSeparateAndRetainsEmptyWarehouses() {
        shift(1, 1, day, LocalTime.of(8, 0), LocalTime.of(17, 0));
        shift(2, 2, day, null, null);
        RoutesEntity route = route(1L, 2L); // driver now belongs to warehouse 1 but this trip left warehouse 2
        var trip = trip(1L, 1L, LocalTime.of(16, 0));
        when(reads.routes(range.getFrom(), range.getTo(), null)).thenReturn(List.of(route));
        when(reads.mileage(range.getFrom(), range.getTo())).thenReturn(List.of(trip));
        var result = service.performance(range, 2L, null, null);
        assertEquals(1, result.warehouses().size()); assertEquals(1, result.workforce().dueShifts());
        assertEquals(0, result.workforce().attendedShifts()); assertEquals(1, result.fleet().startedTrips());
        assertEquals(2L, result.shifts().getFirst().driverId());
        var empty = service.performance(range, 3L, null, null);
        assertEquals(1, empty.warehouses().size()); assertNull(empty.fleet().returnRate());
    }
    @Test void countsRealTripsNotPublishedRoutesAndKeepsZeroDistanceDifferentFromMissing() {
        var settled = trip(1L, 1L, LocalTime.of(16, 0));
        settled.setGpsDistanceKm(0.0); settled.setMileageSettledAt(day.atTime(16, 0));
        var open = trip(2L, 1L, null);
        var invalid = trip(3L, 1L, LocalTime.of(7, 0));
        var legacy = trip(4L, null, LocalTime.of(16, 0)); legacy.setStartOdometer(100); legacy.setEndOdometer(125);
        var future = trip(5L, 1L, null); future.setStartTime(LocalDate.now(ZoneId.of("Asia/Taipei")).plusDays(2).atTime(8, 0));
        when(reads.routes(range.getFrom(), range.getTo(), null)).thenReturn(List.of(route(1L, 1L)));
        when(reads.mileage(range.getFrom(), range.getTo())).thenReturn(List.of(settled, open, invalid, legacy, future));
        var result = service.performance(range, null, null, null);
        assertEquals(4, result.fleet().startedTrips()); assertEquals(2, result.fleet().returnedTrips());
        assertEquals(1, result.fleet().openTrips()); assertEquals(1, result.fleet().invalidTrips());
        assertEquals(1, result.fleet().usedVehicles()); assertEquals(50, result.fleet().returnRate());
        assertEquals(25, result.fleet().actualKm()); assertEquals(2, result.fleet().distanceRecordedTrips());
        assertEquals("LEGACY_ODOMETER", result.trips().get(3).distanceSource());
        assertNull(result.trips().get(1).actualKm());
    }
    @Test void ambiguousLegacyTripGetsItsOwnUnattributedBucketNotGuessedWarehouse() {
        var legacy = trip(1L, null, null);
        when(reads.routes(range.getFrom(), range.getTo(), null)).thenReturn(List.of(route(1L, 1L), route(2L, 2L)));
        when(reads.mileage(range.getFrom(), range.getTo())).thenReturn(List.of(legacy));
        var result = service.performance(range, null, null, null);
        assertEquals(4, result.warehouses().size()); assertNull(result.trips().getFirst().warehouseId());
        assertNull(result.fleet().actualKm()); assertEquals(0, result.fleet().returnRate());
        assertEquals(1, result.warehouses().getLast().fleet().startedTrips());
    }
    @Test void missingShiftTimesHaveNoInventedDenominator() {
        shift(1, 1, day, null, null).setScheduledEndAt(null);
        var result = service.performance(range, null, null, null);
        assertEquals(1, result.workforce().missingTimeShifts()); assertNull(result.workforce().attendanceRate());
        verify(vehicles, never()).save(any());
    }
    @Test void vehicleFilterKeepsMatchingShiftsTripsAndWarehouses() {
        shift(1, 1, day, LocalTime.of(8, 0), LocalTime.of(17, 0));
        var selectedAttendance = new ReportResponses.Attendance();
        selectedAttendance.setShifts(List.of(shifts.getFirst()));
        when(reports.attendance(range, null, 10L)).thenReturn(selectedAttendance);
        when(reads.routes(range.getFrom(), range.getTo(), null)).thenReturn(List.of(route(1L, 1L)));
        var selectedTrip = trip(1L, 1L, LocalTime.of(16, 0));
        var otherTrip = trip(2L, 1L, LocalTime.of(16, 0));
        otherTrip.setVehicleId(20L);
        when(reads.mileage(range.getFrom(), range.getTo())).thenReturn(List.of(selectedTrip, otherTrip));

        var result = service.performance(range, null, null, 10L);

        assertEquals(1, result.workforce().scheduledWorkShifts());
        assertEquals(1, result.fleet().startedTrips());
        assertEquals(1, result.warehouses().size());
        assertEquals(1L, result.warehouses().getFirst().warehouseId());
        verify(reports).attendance(range, null, 10L);
    }
    @Test void tonnageGroupIncludesTripsFromEverySelectedVehicle() {
        var selectedAttendance = new ReportResponses.Attendance();
        selectedAttendance.setShifts(List.of());
        Set<Long> vehicleIds = Set.of(10L, 20L);
        when(reports.attendanceForVehicles(range, null, vehicleIds)).thenReturn(selectedAttendance);
        var first = trip(1L, null, LocalTime.of(16, 0));
        var second = trip(2L, null, LocalTime.of(16, 0));
        second.setVehicleId(20L);
        var other = trip(3L, null, LocalTime.of(16, 0));
        other.setVehicleId(30L);
        when(reads.mileage(range.getFrom(), range.getTo())).thenReturn(List.of(first, second, other));

        var result = service.performanceForVehicles(range, null, null, vehicleIds);

        assertEquals(2, result.fleet().startedTrips());
        verify(reports).attendanceForVehicles(range, null, vehicleIds);
    }
    private RoutesEntity route(long id, long warehouse) {
        var route = new RoutesEntity(); route.setId(id); route.setDriverId(1L); route.setVehicleId(10L);
        route.setDate(day); route.setWarehouseId(warehouse); return route;
    }
    private MileageLogsEntity trip(long id, Long routeId, LocalTime end) {
        var log = new MileageLogsEntity(); log.setId(id); log.setRouteId(routeId); log.setDriverId(1L);
        log.setVehicleId(10L); log.setDate(day); log.setStartTime(day.atTime(8, 0));
        if (end != null) log.setEndTime(day.atTime(end)); return log;
    }
}
