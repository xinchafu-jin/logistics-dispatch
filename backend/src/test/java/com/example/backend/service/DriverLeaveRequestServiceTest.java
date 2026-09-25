package com.example.backend.service;

import com.example.backend.constants.AttendancePunctualityStatus;
import com.example.backend.constants.LeaveRequestStatus;
import com.example.backend.constants.LeaveRequestEventType;
import com.example.backend.constants.LeaveSubmissionSource;
import com.example.backend.constants.LeaveType;
import com.example.backend.constants.ScheduleStatus;
import com.example.backend.constants.ShiftType;
import com.example.backend.dao.AdminUsersDAO;
import com.example.backend.dao.AttendanceRecordsDAO;
import com.example.backend.dao.DriverLeaveRequestsDAO;
import com.example.backend.dao.DriverLeaveRequestEventsDAO;
import com.example.backend.dao.DriverShiftsDAO;
import com.example.backend.dao.DriversDAO;
import com.example.backend.dao.ScheduleMonthsDAO;
import com.example.backend.dto.request.DriverLeaveRequestDTO;
import com.example.backend.entity.AttendanceRecordsEntity;
import com.example.backend.entity.DriverLeaveRequestsEntity;
import com.example.backend.entity.DriverLeaveRequestEventsEntity;
import com.example.backend.entity.DriverShiftsEntity;
import com.example.backend.entity.DriversEntity;
import com.example.backend.entity.ScheduleMonthsEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DriverLeaveRequestServiceTest {
    private final LocalDate workDate = LocalDate.now().plusDays(3);

    private DriverLeaveRequestsDAO requestsDAO;
    private DriverLeaveRequestEventsDAO eventsDAO;
    private DriverShiftsDAO shiftsDAO;
    private ScheduleMonthsDAO monthsDAO;
    private AttendanceRecordsDAO attendanceDAO;
    private DriverScheduleService scheduleService;
    private DriverLeaveRequestService service;
    private DriverShiftsEntity shift;

    @BeforeEach
    void setUp() {
        requestsDAO = mock(DriverLeaveRequestsDAO.class);
        eventsDAO = mock(DriverLeaveRequestEventsDAO.class);
        shiftsDAO = mock(DriverShiftsDAO.class);
        monthsDAO = mock(ScheduleMonthsDAO.class);
        DriversDAO driversDAO = mock(DriversDAO.class);
        AdminUsersDAO adminUsersDAO = mock(AdminUsersDAO.class);
        attendanceDAO = mock(AttendanceRecordsDAO.class);
        scheduleService = mock(DriverScheduleService.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        service = new DriverLeaveRequestService(
                requestsDAO, eventsDAO, shiftsDAO, monthsDAO, driversDAO, adminUsersDAO,
                attendanceDAO, scheduleService, publisher);

        DriversEntity driver = new DriversEntity();
        driver.setId(1L);
        driver.setName("王司機");
        driver.setIsActive(true);
        when(driversDAO.findById(1L)).thenReturn(Optional.of(driver));
        when(adminUsersDAO.existsById(7L)).thenReturn(true);

        shift = new DriverShiftsEntity();
        shift.setId(10L);
        shift.setDriverId(1L);
        shift.setScheduleMonthId(20L);
        shift.setWorkDate(workDate);
        shift.setShiftType(ShiftType.WORK);
        shift.setWorkStart(LocalTime.of(8, 0));
        shift.setWorkEnd(LocalTime.of(17, 0));
        when(shiftsDAO.findForUpdateByDriverIdAndWorkDate(1L, workDate)).thenReturn(Optional.of(shift));
        when(shiftsDAO.findById(10L)).thenReturn(Optional.of(shift));

        ScheduleMonthsEntity month = new ScheduleMonthsEntity();
        month.setId(20L);
        month.setStatus(ScheduleStatus.PUBLISHED);
        when(monthsDAO.findById(20L)).thenReturn(Optional.of(month));
        when(requestsDAO.findByDriverIdAndWorkDateOrderByRequestedAtAsc(1L, workDate))
                .thenReturn(List.of());
        when(requestsDAO.save(any())).thenAnswer(invocation -> {
            DriverLeaveRequestsEntity request = invocation.getArgument(0);
            request.setId(100L);
            request.setRequestedAt(LocalDateTime.now());
            return request;
        });
        when(requestsDAO.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void 司機可以送出有原因的部分時段病假() {
        DriverLeaveRequestDTO dto = new DriverLeaveRequestDTO();
        dto.setWorkDate(workDate);
        dto.setLeaveType(LeaveType.SICK);
        dto.setLeaveStart(LocalTime.of(13, 0));
        dto.setLeaveEnd(LocalTime.of(17, 0));
        dto.setReason("下午看醫生");

        var response = service.submit(1L, dto);

        assertEquals(LeaveType.SICK, response.leaveType());
        assertEquals(LocalTime.of(13, 0), response.leaveStart());
        assertFalse(response.fullDay());
        assertEquals(LeaveRequestStatus.PENDING, response.status());
    }

    @Test
    void 司機不能自行把假別填成曠職() {
        DriverLeaveRequestDTO dto = new DriverLeaveRequestDTO();
        dto.setWorkDate(workDate);
        dto.setLeaveType(LeaveType.ABSENT);
        dto.setReason("自行填寫曠職");

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.submit(1L, dto));

        assertEquals("曠職只能由主管依出勤狀況認定", error.getMessage());
    }

    @Test
    void 早上遲到可填部分時段特殊事由且不會變成整天假() {
        DriverLeaveRequestDTO dto = new DriverLeaveRequestDTO();
        dto.setWorkDate(workDate);
        dto.setLeaveType(LeaveType.SPECIAL);
        dto.setLeaveStart(LocalTime.of(8, 0));
        dto.setLeaveEnd(LocalTime.of(9, 0));
        dto.setReason("早上臨時處理家務");

        var response = service.submit(1L, dto);

        assertFalse(response.fullDay());
        assertEquals(LocalTime.of(8, 0), response.leaveStart());
        assertEquals(LocalTime.of(9, 0), response.leaveEnd());
    }

    @Test
    void 特殊事由也可申請整天假() {
        DriverLeaveRequestDTO dto = new DriverLeaveRequestDTO();
        dto.setWorkDate(workDate);
        dto.setLeaveType(LeaveType.SPECIAL);
        dto.setReason("家庭行程");

        var response = service.submit(1L, dto);

        assertTrue(response.fullDay());
    }

    @Test
    void 核准整天假會把班表改為請假() {
        DriverLeaveRequestsEntity request = pending(true, null, null);
        when(requestsDAO.findForUpdate(100L)).thenReturn(Optional.of(request));

        service.approve(100L, LeaveType.ANNUAL, "同意使用年假", 7L, "admin001");

        verify(scheduleService).markLeave(10L, "家庭行程", null);
        assertEquals(LeaveRequestStatus.APPROVED, request.getStatus());
        assertEquals(LeaveType.ANNUAL, request.getLeaveType());
        assertEquals("同意使用年假", request.getDecisionReason());
    }

    @Test
    void 核准涵蓋遲到時段後會解除補假警示但保留分鐘() {
        DriverLeaveRequestsEntity request = pending(
                false, LocalTime.of(8, 0), LocalTime.of(9, 0));
        when(requestsDAO.findForUpdate(100L)).thenReturn(Optional.of(request));

        AttendanceRecordsEntity attendance = new AttendanceRecordsEntity();
        attendance.setDriverId(1L);
        attendance.setWorkDate(workDate);
        attendance.setClockInAt(workDate.atTime(8, 40));
        attendance.setLeaveRequired(true);
        attendance.setLeaveRequiredMinutes(40);
        attendance.setPunctualityStatus(AttendancePunctualityStatus.LEAVE_REQUIRED);
        when(attendanceDAO.findForUpdate(1L, workDate)).thenReturn(Optional.of(attendance));

        service.approve(100L, LeaveType.SPECIAL, "同意補登特殊事由", 7L, "admin001");

        assertFalse(attendance.getLeaveRequired());
        assertEquals(40, attendance.getLeaveRequiredMinutes());
        assertEquals(AttendancePunctualityStatus.LEAVE_COVERED, attendance.getPunctualityStatus());
        assertEquals(100L, attendance.getCoveredLeaveRequestId());
        verify(attendanceDAO).save(attendance);
    }

    @Test
    void 主管可以把已核准特殊事由改成病假並留下理由() {
        DriverLeaveRequestsEntity request = pending(false, LocalTime.of(8, 0), LocalTime.of(9, 0));
        request.setStatus(LeaveRequestStatus.APPROVED);
        when(requestsDAO.findForUpdate(100L)).thenReturn(Optional.of(request));

        service.correctType(100L, LeaveType.SICK, "已補看診證明", 7L, "admin001");

        assertEquals(LeaveType.SPECIAL, request.getRequestedLeaveType());
        assertEquals(LeaveType.SICK, request.getLeaveType());
        assertEquals("已補看診證明", request.getTypeChangeReason());

        ArgumentCaptor<DriverLeaveRequestEventsEntity> event =
                ArgumentCaptor.forClass(DriverLeaveRequestEventsEntity.class);
        verify(eventsDAO).save(event.capture());
        assertEquals(LeaveRequestEventType.TYPE_CHANGED, event.getValue().getEventType());
        assertEquals(LeaveType.SPECIAL, event.getValue().getOldLeaveType());
        assertEquals(LeaveType.SICK, event.getValue().getNewLeaveType());
        assertEquals("admin001", event.getValue().getActorAccount());
        assertEquals("已補看診證明", event.getValue().getReason());
    }

    @Test
    void 整班未打卡會自動建立待審特殊事由() {
        LocalDate missedDate = LocalDate.now().minusDays(1);
        DriverShiftsEntity missedShift = new DriverShiftsEntity();
        missedShift.setId(11L);
        missedShift.setDriverId(1L);
        missedShift.setScheduleMonthId(20L);
        missedShift.setWorkDate(missedDate);
        missedShift.setShiftType(ShiftType.WORK);
        missedShift.setWorkStart(LocalTime.of(8, 0));
        missedShift.setWorkEnd(LocalTime.of(17, 0));
        when(shiftsDAO.findAllByWorkDate(missedDate)).thenReturn(List.of(missedShift));
        when(shiftsDAO.findForUpdateByDriverIdAndWorkDate(1L, missedDate))
                .thenReturn(Optional.of(missedShift));
        when(requestsDAO.findByDriverIdAndWorkDateOrderByRequestedAtAsc(1L, missedDate))
                .thenReturn(List.of());

        service.createNoShowRequestsForDate(
                missedDate, missedDate.plusDays(1).atTime(1, 0));

        ArgumentCaptor<DriverLeaveRequestsEntity> saved =
                ArgumentCaptor.forClass(DriverLeaveRequestsEntity.class);
        verify(requestsDAO).save(saved.capture());
        assertEquals(LeaveType.SPECIAL, saved.getValue().getLeaveType());
        assertEquals(LeaveSubmissionSource.SYSTEM, saved.getValue().getSubmissionSource());
        assertEquals(LeaveRequestStatus.PENDING, saved.getValue().getStatus());
        assertTrue(saved.getValue().getFullDay());

        ArgumentCaptor<DriverLeaveRequestEventsEntity> event =
                ArgumentCaptor.forClass(DriverLeaveRequestEventsEntity.class);
        verify(eventsDAO).save(event.capture());
        assertEquals(LeaveRequestEventType.AUTO_NO_SHOW_CREATED, event.getValue().getEventType());
        assertEquals("SYSTEM", event.getValue().getActorAccount());
    }

    @Test
    void 主管可把系統建立的特殊事由認定為曠職且保留原上班班表() {
        DriverLeaveRequestsEntity request = pending(true, null, null);
        request.setSubmissionSource(LeaveSubmissionSource.SYSTEM);
        when(requestsDAO.findForUpdate(100L)).thenReturn(Optional.of(request));

        service.approve(100L, LeaveType.ABSENT, "未提出任何合理原因", 7L, "admin001");

        assertEquals(LeaveType.ABSENT, request.getLeaveType());
        verify(scheduleService, never()).markLeave(any(), any(), any());
    }

    private DriverLeaveRequestsEntity pending(
            boolean fullDay,
            LocalTime start,
            LocalTime end
    ) {
        DriverLeaveRequestsEntity request = new DriverLeaveRequestsEntity();
        request.setId(100L);
        request.setDriverId(1L);
        request.setDriverShiftId(10L);
        request.setWorkDate(workDate);
        request.setRequestedLeaveType(LeaveType.SPECIAL);
        request.setLeaveType(LeaveType.SPECIAL);
        request.setFullDay(fullDay);
        request.setLeaveStart(start);
        request.setLeaveEnd(end);
        request.setRequestReason("家庭行程");
        request.setRequestedAt(LocalDateTime.now());
        return request;
    }
}
