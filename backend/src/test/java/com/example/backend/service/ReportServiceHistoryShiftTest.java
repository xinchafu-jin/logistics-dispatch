package com.example.backend.service;

import com.example.backend.constants.ShiftType;
import com.example.backend.dao.DriversDAO;
import com.example.backend.dao.OrdersDAO;
import com.example.backend.dao.ReportReadDAO;
import com.example.backend.dao.RoutesDAO;
import com.example.backend.dao.StoresDAO;
import com.example.backend.dao.VehiclesDAO;
import com.example.backend.dao.WarehousesDAO;
import com.example.backend.entity.AttendanceRecordsEntity;
import com.example.backend.entity.DriverShiftsEntity;
import com.example.backend.entity.DriversEntity;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReportServiceHistoryShiftTest {
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    @Test
    void futurePublishedShiftsStayOutOfAttendanceHistoryAndDriverTotals() {
        LocalDate today = LocalDate.now(TAIPEI);
        LocalDate yesterday = today.minusDays(1);
        LocalDate tomorrow = today.plusDays(1);
        ReportService.Range range = new ReportService.Range(yesterday, tomorrow);
        ReportReadDAO reads = mock(ReportReadDAO.class);
        DriversDAO drivers = mock(DriversDAO.class);
        DriversEntity driver = new DriversEntity();
        driver.setId(7L);
        driver.setName("司機甲");
        when(drivers.findAll()).thenReturn(List.of(driver));
        when(reads.publishedShifts(yesterday, tomorrow)).thenReturn(List.of(
                workShift(1L, yesterday, LocalTime.of(8, 0)),
                workShift(2L, tomorrow, LocalTime.of(8, 0))));
        ReportService reports = new ReportService(reads, mock(OrdersDAO.class), mock(RoutesDAO.class),
                drivers, mock(VehiclesDAO.class), mock(WarehousesDAO.class), mock(StoresDAO.class));

        var attendance = reports.attendance(range, null);
        assertEquals(1, attendance.getScheduledWorkShifts());
        assertEquals(List.of(yesterday), attendance.getShifts().stream().map(row -> row.getWorkDate()).toList());
        assertEquals(1, reports.drivers(range, null).getDrivers().get(0).getScheduledWorkDays());
    }

    @Test
    void sameDayShiftOnlyBecomesHistoryAfterStartOrActualEarlyPunch() {
        LocalDate today = LocalDate.of(2026, 9, 29);
        LocalDateTime now = today.atTime(7, 30);
        DriverShiftsEntity shift = workShift(1L, today, LocalTime.of(8, 0));

        assertFalse(ReportService.hasOccurredForHistory(shift, null, now));
        AttendanceRecordsEntity earlyPunch = new AttendanceRecordsEntity();
        earlyPunch.setClockInAt(today.atTime(7, 20));
        assertTrue(ReportService.hasOccurredForHistory(shift, earlyPunch, now));
        assertTrue(ReportService.hasOccurredForHistory(shift, null, today.atTime(8, 0)));
        assertFalse(ReportService.hasOccurredForHistory(
                workShift(2L, today.plusDays(1), LocalTime.of(8, 0)), null, now));
    }

    private static DriverShiftsEntity workShift(Long id, LocalDate date, LocalTime start) {
        DriverShiftsEntity shift = new DriverShiftsEntity();
        shift.setId(id);
        shift.setDriverId(7L);
        shift.setWorkDate(date);
        shift.setShiftType(ShiftType.WORK);
        shift.setWorkStart(start);
        shift.setWorkEnd(LocalTime.of(17, 0));
        return shift;
    }
}
