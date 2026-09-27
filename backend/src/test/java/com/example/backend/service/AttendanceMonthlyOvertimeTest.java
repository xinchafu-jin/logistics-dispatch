package com.example.backend.service;

import com.example.backend.dao.AttendanceRecordsDAO;
import com.example.backend.dao.DriverShiftsDAO;
import com.example.backend.dao.DriversDAO;
import com.example.backend.dao.EmergencyLeaveRequestsDAO;
import com.example.backend.dao.MileageLogsDAO;
import com.example.backend.dao.ScheduleMonthsDAO;
import com.example.backend.entity.AttendanceRecordsEntity;
import com.example.backend.entity.DriverShiftsEntity;
import com.example.backend.entity.ScheduleMonthsEntity;
import com.example.backend.constants.ScheduleStatus;
import com.example.backend.constants.ShiftType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class AttendanceMonthlyOvertimeTest {
    @Test
    void recalculatesCompletedDaysAndDoesNotCountOpenTodayOrStaleHistoricalCache() {
        AttendanceRecordsDAO attendance = mock(AttendanceRecordsDAO.class);
        DriverShiftsDAO shifts = mock(DriverShiftsDAO.class);
        AttendanceService service = service(attendance, shifts);
        LocalDateTime asOf = LocalDateTime.of(2026, 9, 27, 18, 45);
        AttendanceRecordsEntity earlier = new AttendanceRecordsEntity();
        earlier.setWorkDate(LocalDate.of(2026, 9, 26));
        earlier.setDriverShiftId(5L);
        earlier.setClockInAt(earlier.getWorkDate().atTime(8, 0));
        earlier.setClockOutAt(earlier.getWorkDate().atTime(18, 5));
        earlier.setOvertimeMinutes(103086); // stale cache is never trusted by resource cards
        AttendanceRecordsEntity today = new AttendanceRecordsEntity();
        today.setWorkDate(asOf.toLocalDate());
        today.setDriverShiftId(5L);
        today.setClockInAt(asOf.toLocalDate().atTime(8, 0));
        DriverShiftsEntity shift = new DriverShiftsEntity();
        shift.setWorkStart(LocalTime.of(8, 0));
        shift.setWorkEnd(LocalTime.of(17, 0));
        shift.setDriverId(7L);
        shift.setShiftType(ShiftType.WORK);
        shift.setScheduleMonthId(1L);
        ScheduleMonthsDAO months = mock(ScheduleMonthsDAO.class);
        ScheduleMonthsEntity month = new ScheduleMonthsEntity(); month.setStatus(ScheduleStatus.PUBLISHED);
        when(months.findById(1L)).thenReturn(Optional.of(month));
        service = new AttendanceService(attendance, shifts, months, mock(DriversDAO.class),
                mock(MileageLogsDAO.class), mock(EmergencyLeaveRequestsDAO.class), mock(WarehouseProximityService.class));
        when(shifts.findById(5L)).thenReturn(Optional.of(shift));
        when(attendance.findByDriverIdAndWorkDateBetweenOrderByWorkDateAsc(
                7L, LocalDate.of(2026, 9, 1), asOf.toLocalDate())).thenReturn(List.of(earlier, today));

        assertEquals(65, service.monthlyOvertimeMinutes(7L, asOf));
        assertEquals(1, service.monthlyOvertimeSummary(7L, asOf).unsettledShifts());
        assertEquals(103086, earlier.getOvertimeMinutes()); // read-only, does not overwrite original attendance
        verify(attendance, never()).save(any());
        verify(attendance, times(2)).findByDriverIdAndWorkDateBetweenOrderByWorkDateAsc(
                7L, LocalDate.of(2026, 9, 1), asOf.toLocalDate());
    }

    @Test
    void recordedMinutesExcludeBreakAndLateArrivalAndRejectMissingOrInvalidPunches() {
        LocalDateTime day = LocalDateTime.of(2026, 9, 20, 17, 0);
        LocalDateTime asOf = day.plusDays(2);
        assertEquals(40L, RecordedOvertime.minutes(day.minusHours(9), day.plusHours(1), day,
                day.plusMinutes(10), day.plusMinutes(30), asOf));
        assertEquals(15L, RecordedOvertime.minutes(day.plusMinutes(45), day.plusHours(1), day, null, null, asOf));
        assertEquals(0L, RecordedOvertime.minutes(day.minusHours(9), day.plusSeconds(59), day, null, null, asOf));
        org.junit.jupiter.api.Assertions.assertNull(RecordedOvertime.minutes(day.minusHours(9), null, day, null, null, asOf));
        org.junit.jupiter.api.Assertions.assertNull(RecordedOvertime.minutes(day, day.minusMinutes(1), day, null, null, asOf));
        org.junit.jupiter.api.Assertions.assertNull(RecordedOvertime.minutes(day, asOf.plusMinutes(1), day, null, null, asOf));
        // Overnight shift scheduled end is already resolved to next morning by both callers.
        assertEquals(20L, RecordedOvertime.minutes(day.plusHours(3), day.plusHours(13).plusMinutes(20),
                day.plusHours(13), null, null, asOf));
    }

    @Test
    void noAttendanceThisMonthIsZeroAndQueryResetsAtNewYear() {
        AttendanceRecordsDAO attendance = mock(AttendanceRecordsDAO.class);
        AttendanceService service = service(attendance, mock(DriverShiftsDAO.class));
        LocalDateTime asOf = LocalDateTime.of(2027, 1, 2, 12, 0);
        when(attendance.findByDriverIdAndWorkDateBetweenOrderByWorkDateAsc(
                7L, LocalDate.of(2027, 1, 1), asOf.toLocalDate())).thenReturn(List.of());

        assertEquals(0, service.monthlyOvertimeMinutes(7L, asOf));
    }

    private AttendanceService service(AttendanceRecordsDAO attendance, DriverShiftsDAO shifts) {
        return new AttendanceService(attendance, shifts, mock(ScheduleMonthsDAO.class),
                mock(DriversDAO.class), mock(MileageLogsDAO.class),
                mock(EmergencyLeaveRequestsDAO.class), mock(WarehouseProximityService.class));
    }
}
