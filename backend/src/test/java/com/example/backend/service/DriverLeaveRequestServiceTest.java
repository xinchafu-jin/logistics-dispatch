package com.example.backend.service;

import com.example.backend.constants.AttendancePunctualityStatus;
import com.example.backend.constants.LeaveRequestStatus;
import com.example.backend.constants.LeaveRequestEventType;
import com.example.backend.constants.LeaveRequestMode;
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
import com.example.backend.dto.request.DriverMakeupLeaveRequestDTO;
import com.example.backend.dto.request.DriverPlannedLeaveBatchRequestDTO;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DriverLeaveRequestServiceTest {
    private final LocalDate workDate = LocalDate.now().plusDays(3);
    private final LocalDate temporaryDate = LocalDate.now();

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
        DriverShiftsEntity temporaryShift = publishedWorkShift(9L, temporaryDate);
        when(shiftsDAO.findForUpdateByDriverIdAndWorkDate(1L, temporaryDate))
                .thenReturn(Optional.of(temporaryShift));

        ScheduleMonthsEntity month = new ScheduleMonthsEntity();
        month.setId(20L);
        month.setStatus(ScheduleStatus.PUBLISHED);
        when(monthsDAO.findById(20L)).thenReturn(Optional.of(month));
        when(requestsDAO.findByDriverIdAndWorkDateOrderByRequestedAtAsc(1L, workDate))
                .thenReturn(List.of());
        when(requestsDAO.findByDriverIdAndWorkDateOrderByRequestedAtAsc(1L, temporaryDate))
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
        dto.setWorkDate(temporaryDate);
        dto.setLeaveType(LeaveType.SICK);
        dto.setLeaveStart(LocalTime.of(13, 0));
        dto.setLeaveEnd(LocalTime.of(17, 0));
        dto.setReason("下午看醫生");

        var response = service.submit(1L, dto);

        assertEquals(LeaveType.SICK, response.leaveType());
        assertEquals(LocalTime.of(13, 0), response.leaveStart());
        assertFalse(response.fullDay());
        assertEquals(LeaveRequestMode.TEMPORARY, response.requestMode());
        assertEquals(LeaveRequestStatus.PENDING, response.status());
    }

    @Test
    void 司機不能自行把假別填成曠職() {
        DriverLeaveRequestDTO dto = new DriverLeaveRequestDTO();
        dto.setWorkDate(temporaryDate);
        dto.setLeaveType(LeaveType.ABSENT);
        dto.setReason("自行填寫曠職");

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.submit(1L, dto));

        assertEquals("曠職只能由主管依出勤狀況認定", error.getMessage());
    }

    @Test
    void 司機可替過去整天未打卡日期補原因與選填照片() {
        LocalDate missedDate = LocalDate.now().minusDays(1);
        DriverShiftsEntity missedShift = new DriverShiftsEntity();
        missedShift.setId(88L);
        missedShift.setDriverId(1L);
        missedShift.setScheduleMonthId(20L);
        missedShift.setWorkDate(missedDate);
        missedShift.setShiftType(ShiftType.WORK);
        missedShift.setWorkStart(LocalTime.of(8, 0));
        missedShift.setWorkEnd(LocalTime.of(17, 0));
        when(shiftsDAO.findForUpdateByDriverIdAndWorkDate(1L, missedDate))
                .thenReturn(Optional.of(missedShift));

        DriverLeaveRequestsEntity automatic = new DriverLeaveRequestsEntity();
        automatic.setId(501L);
        automatic.setDriverId(1L);
        automatic.setDriverShiftId(88L);
        automatic.setWorkDate(missedDate);
        automatic.setRequestedLeaveType(LeaveType.SPECIAL);
        automatic.setLeaveType(LeaveType.SPECIAL);
        automatic.setFullDay(true);
        automatic.setRequestReason("系統未到紀錄");
        automatic.setSubmissionSource(LeaveSubmissionSource.SYSTEM);
        automatic.setRequestedAt(LocalDateTime.now().minusHours(2));
        when(requestsDAO.findForUpdateByDriverIdAndWorkDate(1L, missedDate))
                .thenReturn(List.of(automatic));

        DriverMakeupLeaveRequestDTO dto = new DriverMakeupLeaveRequestDTO();
        dto.setWorkDate(missedDate);
        dto.setLeaveType(LeaveType.SICK);
        dto.setReason("發燒就醫，隔日補單");
        dto.setEvidencePhotoUrl(
                "/uploads/leave-evidence/123e4567-e89b-12d3-a456-426614174000.jpg");

        var response = service.submitMakeupLeave(1L, dto);

        assertEquals(501L, response.id());
        assertEquals(LeaveType.SICK, response.leaveType());
        assertEquals("發燒就醫，隔日補單", response.requestReason());
        assertEquals(dto.getEvidencePhotoUrl(), response.evidencePhotoUrl());
        assertEquals(LeaveRequestMode.MAKEUP, response.requestMode());
        assertEquals(LeaveRequestStatus.PENDING, response.status());
        ArgumentCaptor<DriverLeaveRequestEventsEntity> eventCaptor =
                ArgumentCaptor.forClass(DriverLeaveRequestEventsEntity.class);
        verify(eventsDAO).save(eventCaptor.capture());
        assertEquals(LeaveRequestEventType.DRIVER_EXPLANATION_SUBMITTED,
                eventCaptor.getValue().getEventType());
        assertEquals(LeaveType.SPECIAL, eventCaptor.getValue().getOldLeaveType());
        assertEquals(LeaveType.SICK, eventCaptor.getValue().getNewLeaveType());
    }

    @Test
    void 未來日期不能誤走臨請入口() {
        DriverLeaveRequestDTO dto = new DriverLeaveRequestDTO();
        dto.setWorkDate(workDate);
        dto.setLeaveType(LeaveType.ANNUAL);
        dto.setReason("未來排假");

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.submit(1L, dto));

        assertEquals("未來日期請使用預排請假功能", error.getMessage());
    }

    @Test
    void 主管核准過去補請假會保留原歷史班表() {
        LocalDate missedDate = LocalDate.now().minusDays(1);
        DriverShiftsEntity missedShift = new DriverShiftsEntity();
        missedShift.setId(88L);
        missedShift.setDriverId(1L);
        missedShift.setScheduleMonthId(20L);
        missedShift.setWorkDate(missedDate);
        missedShift.setShiftType(ShiftType.WORK);
        missedShift.setWorkStart(LocalTime.of(8, 0));
        missedShift.setWorkEnd(LocalTime.of(17, 0));
        when(shiftsDAO.findById(88L)).thenReturn(Optional.of(missedShift));

        DriverLeaveRequestsEntity makeup = new DriverLeaveRequestsEntity();
        makeup.setId(502L);
        makeup.setDriverId(1L);
        makeup.setDriverShiftId(88L);
        makeup.setWorkDate(missedDate);
        makeup.setRequestedLeaveType(LeaveType.SICK);
        makeup.setLeaveType(LeaveType.SICK);
        makeup.setFullDay(true);
        makeup.setRequestReason("事後補病假");
        makeup.setSubmissionSource(LeaveSubmissionSource.DRIVER);
        makeup.setRequestedAt(LocalDateTime.now());
        when(requestsDAO.findForUpdate(502L)).thenReturn(Optional.of(makeup));

        var response = service.approve(502L, null, "同意補病假", 7L, "admin001");

        assertEquals(LeaveRequestStatus.APPROVED, response.status());
        verify(scheduleService, never()).markLeave(any(), any(), any());
    }

    @Test
    void 早上遲到可填部分時段特殊事由且不會變成整天假() {
        DriverLeaveRequestDTO dto = new DriverLeaveRequestDTO();
        dto.setWorkDate(temporaryDate);
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
        dto.setWorkDate(temporaryDate);
        dto.setLeaveType(LeaveType.SPECIAL);
        dto.setReason("家庭行程");

        var response = service.submit(1L, dto);

        assertTrue(response.fullDay());
    }

    @Test
    void 核准整天臨請會保留原班表() {
        DriverLeaveRequestsEntity request = pending(true, null, null);
        when(requestsDAO.findForUpdate(100L)).thenReturn(Optional.of(request));

        service.approve(100L, LeaveType.ANNUAL, "同意使用年假", 7L, "admin001");

        verify(scheduleService, never()).markLeave(any(), any(), any());
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
        assertEquals(LeaveRequestMode.SYSTEM_NO_SHOW, saved.getValue().getRequestMode());
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

    @Test
    void 不同假別會分組且同一假別可一次申請多日() {
        LocalDate sickDate2 = workDate.plusDays(1);
        LocalDate annualDate = workDate.plusDays(2);
        DriverShiftsEntity sickShift2 = publishedWorkShift(11L, sickDate2);
        DriverShiftsEntity annualShift = publishedWorkShift(12L, annualDate);
        when(shiftsDAO.findForUpdateByDriverIdAndWorkDate(1L, sickDate2))
                .thenReturn(Optional.of(sickShift2));
        when(shiftsDAO.findForUpdateByDriverIdAndWorkDate(1L, annualDate))
                .thenReturn(Optional.of(annualShift));
        when(requestsDAO.findByDriverIdAndWorkDateOrderByRequestedAtAsc(1L, sickDate2))
                .thenReturn(List.of());
        when(requestsDAO.findByDriverIdAndWorkDateOrderByRequestedAtAsc(1L, annualDate))
                .thenReturn(List.of());

        DriverPlannedLeaveBatchRequestDTO.Group sick = new DriverPlannedLeaveBatchRequestDTO.Group();
        sick.setLeaveType(LeaveType.SICK);
        sick.setWorkDates(List.of(workDate, sickDate2));
        sick.setReason("定期回診");
        DriverPlannedLeaveBatchRequestDTO.Group annual = new DriverPlannedLeaveBatchRequestDTO.Group();
        annual.setLeaveType(LeaveType.ANNUAL);
        annual.setWorkDates(List.of(annualDate));
        annual.setReason("家庭旅遊");
        DriverPlannedLeaveBatchRequestDTO dto = new DriverPlannedLeaveBatchRequestDTO();
        dto.setGroups(List.of(sick, annual));

        var response = service.submitPlannedBatches(1L, dto);

        assertEquals(2, response.size());
        assertEquals(2, response.get(0).workDates().size());
        assertEquals(LeaveType.SICK, response.get(0).leaveType());
        assertEquals(LeaveType.ANNUAL, response.get(1).leaveType());
        assertNotEquals(response.get(0).batchId(), response.get(1).batchId());
        verify(requestsDAO, times(3)).save(any(DriverLeaveRequestsEntity.class));
    }

    @Test
    void 主管核准群組會一次核准全部日期並修改每天班表() {
        DriverLeaveRequestsEntity first = pending(true, null, null);
        first.setBatchId("batch-001");
        first.setRequestMode(LeaveRequestMode.PREPLANNED);
        DriverLeaveRequestsEntity second = pending(true, null, null);
        second.setId(101L);
        second.setBatchId("batch-001");
        second.setRequestMode(LeaveRequestMode.PREPLANNED);
        second.setDriverShiftId(11L);
        second.setWorkDate(workDate.plusDays(1));
        DriverShiftsEntity secondShift = publishedWorkShift(11L, workDate.plusDays(1));
        when(shiftsDAO.findById(11L)).thenReturn(Optional.of(secondShift));
        when(requestsDAO.findBatchForUpdate("batch-001")).thenReturn(List.of(first, second));

        var response = service.approveBatch(
                "batch-001", "人力已安排完成", 7L, "admin001");

        assertEquals(LeaveRequestStatus.APPROVED, first.getStatus());
        assertEquals(LeaveRequestStatus.APPROVED, second.getStatus());
        assertEquals("人力已安排完成", first.getDecisionReason());
        assertEquals(2, response.workDates().size());
        verify(scheduleService, times(2)).markLeave(any(), any(), any());
    }

    @Test
    void 群組申請不能用單筆端點拆開審核() {
        DriverLeaveRequestsEntity request = pending(true, null, null);
        request.setBatchId("batch-001");
        when(requestsDAO.findForUpdate(100L)).thenReturn(Optional.of(request));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.approve(100L, null, "只核准其中一天", 7L, "admin001"));

        assertEquals("這是多日預排請假，必須使用群組審核功能", error.getMessage());
        verify(scheduleService, never()).markLeave(any(), any(), any());
    }

    private DriverShiftsEntity publishedWorkShift(Long id, LocalDate date) {
        DriverShiftsEntity item = new DriverShiftsEntity();
        item.setId(id);
        item.setDriverId(1L);
        item.setScheduleMonthId(20L);
        item.setWorkDate(date);
        item.setShiftType(ShiftType.WORK);
        item.setWorkStart(LocalTime.of(8, 0));
        item.setWorkEnd(LocalTime.of(17, 0));
        return item;
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
