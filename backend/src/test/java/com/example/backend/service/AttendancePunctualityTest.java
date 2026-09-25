package com.example.backend.service;

import com.example.backend.constants.AttendancePunctualityStatus;
import com.example.backend.dao.AttendanceRecordsDAO;
import com.example.backend.dao.DriverShiftsDAO;
import com.example.backend.dao.DriversDAO;
import com.example.backend.dao.EmergencyLeaveRequestsDAO;
import com.example.backend.dao.MileageLogsDAO;
import com.example.backend.dao.ScheduleMonthsDAO;
import com.example.backend.entity.AttendanceRecordsEntity;
import com.example.backend.entity.DriverShiftsEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AttendancePunctualityTest {
    private final LocalDate workDate = LocalDate.of(2026, 9, 25);
    private AttendanceRecordsDAO attendanceDAO;
    private AttendanceService service;
    private AttendanceRecordsEntity attendance;
    private DriverShiftsEntity shift;

    @BeforeEach
    void setUp() {
        attendanceDAO = mock(AttendanceRecordsDAO.class);
        service = new AttendanceService(
                attendanceDAO,
                mock(DriverShiftsDAO.class),
                mock(ScheduleMonthsDAO.class),
                mock(DriversDAO.class),
                mock(MileageLogsDAO.class),
                mock(EmergencyLeaveRequestsDAO.class),
                mock(WarehouseProximityService.class));

        attendance = new AttendanceRecordsEntity();
        attendance.setDriverId(1L);
        attendance.setWorkDate(workDate);
        shift = new DriverShiftsEntity();
        shift.setWorkStart(LocalTime.of(8, 0));
    }

    @Test
    void 每月第一次三十分鐘內遲到自動赦免() {
        service.applyPunctuality(attendance, shift, workDate.atTime(8, 10));

        assertEquals(10, attendance.getLateMinutes());
        assertEquals(AttendancePunctualityStatus.LATE_EXCUSED, attendance.getPunctualityStatus());
        assertTrue(attendance.getLateExcused());
    }

    @Test
    void 當月赦免用過後三十分鐘內仍記遲到() {
        when(attendanceDAO.existsByDriverIdAndWorkDateBetweenAndLateExcusedTrue(
                1L, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30))).thenReturn(true);

        service.applyPunctuality(attendance, shift, workDate.atTime(8, 20));

        assertEquals(AttendancePunctualityStatus.LATE, attendance.getPunctualityStatus());
        assertFalse(attendance.getLateExcused());
    }

    @Test
    void 超過三十分鐘要求補請假且不消耗赦免() {
        service.applyPunctuality(attendance, shift, workDate.atTime(8, 31));

        assertEquals(31, attendance.getLateMinutes());
        assertEquals(AttendancePunctualityStatus.LEAVE_REQUIRED, attendance.getPunctualityStatus());
        assertTrue(attendance.getLeaveRequired());
        assertEquals(31, attendance.getLeaveRequiredMinutes());
        assertFalse(attendance.getLateExcused());
    }
}
