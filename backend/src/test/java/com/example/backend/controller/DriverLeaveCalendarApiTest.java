package com.example.backend.controller;

import com.example.backend.constants.*;
import com.example.backend.dao.*;
import com.example.backend.entity.*;
import com.example.backend.service.AuthService;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.time.*;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 本機整合測試：示範司機／班次／申請均於每個測試後 rollback，不留下營運資料。 */
@SpringBootTest(properties = {"app.crypto.password=test-only-password-test-only-password", "app.crypto.salt=0123456789abcdef"})
@Transactional
class DriverLeaveCalendarApiTest {
    @Autowired WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") Filter security;
    @Autowired JwtEncoder encoder;
    @Autowired DriversDAO drivers;
    @Autowired ScheduleMonthsDAO months;
    @Autowired DriverShiftsDAO shifts;
    @Autowired AttendanceRecordsDAO attendance;
    @Autowired DriverLeaveRequestsDAO requests;
    MockMvc mvc;
    Long driverId;
    String driverToken, adminToken;
    final LocalDate today = LocalDate.now(ZoneId.of("Asia/Taipei"));

    @BeforeEach void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(security).build();
        DriversEntity driver = new DriversEntity();
        driver.setAccount("LC-" + UUID.randomUUID().toString().substring(0, 12)); driver.setName("請假日曆測試");
        driver.setWorkStart(LocalTime.of(8, 0)); driver.setWorkEnd(LocalTime.of(17, 0)); driver.setRestDuration(60);
        driver.setPassword("test-only-not-used-for-login");
        driverId = drivers.saveAndFlush(driver).getId();
        driverToken = token(AuthService.ROLE_DRIVER, driverId); adminToken = token(AuthService.ROLE_ADMIN, 1L);
    }

    DriverShiftsEntity work(LocalDate date, ShiftType type) {
        ScheduleMonthsEntity month = months.findByScheduleMonth(date.withDayOfMonth(1)).orElseGet(() -> {
            ScheduleMonthsEntity created = new ScheduleMonthsEntity();
            created.setScheduleMonth(date.withDayOfMonth(1)); created.setGeneratedAt(LocalDateTime.now()); return created;
        });
        month.setStatus(ScheduleStatus.PUBLISHED); month = months.saveAndFlush(month);
        DriverShiftsEntity shift = new DriverShiftsEntity();
        shift.setDriverId(driverId); shift.setScheduleMonthId(month.getId()); shift.setWorkDate(date);
        shift.setShiftType(type); shift.setWorkStart(LocalTime.of(8, 0)); shift.setWorkEnd(LocalTime.of(17, 0));
        return shifts.saveAndFlush(shift);
    }

    void pending(DriverShiftsEntity shift, boolean system) {
        DriverLeaveRequestsEntity request = new DriverLeaveRequestsEntity();
        request.setDriverId(driverId); request.setDriverShiftId(shift.getId()); request.setWorkDate(shift.getWorkDate());
        request.setRequestedLeaveType(LeaveType.SPECIAL); request.setLeaveType(LeaveType.SPECIAL);
        request.setFullDay(true); request.setRequestReason("測試紀錄");
        request.setSubmissionSource(system ? LeaveSubmissionSource.SYSTEM : LeaveSubmissionSource.DRIVER);
        request.setRequestMode(system ? LeaveRequestMode.SYSTEM_NO_SHOW : LeaveRequestMode.MAKEUP);
        requests.saveAndFlush(request);
    }

    String payload(List<LocalDate> dates) {
        return "{\"workDates\":[" + dates.stream().map(date -> "\"" + date + "\"").reduce((a, b) -> a + "," + b).orElse("")
                + "],\"leaveType\":\"SICK\",\"reason\":\"連續發燒，補充就醫原因\"}";
    }

    @Test void 候選日期過濾休假重複申請但不以打卡鎖住上班日() throws Exception {
        LocalDate available = today.minusDays(1), system = today.minusDays(5);
        work(available, ShiftType.WORK); pending(work(system, ShiftType.WORK), true);
        pending(work(today.minusDays(4), ShiftType.WORK), false);
        work(today.minusDays(3), ShiftType.DAY_OFF);
        DriverShiftsEntity checked = work(today.minusDays(2), ShiftType.WORK);
        AttendanceRecordsEntity record = new AttendanceRecordsEntity();
        record.setDriverId(driverId); record.setDriverShiftId(checked.getId()); record.setWorkDate(checked.getWorkDate());
        record.setClockInAt(checked.getWorkDate().atTime(8, 0)); attendance.saveAndFlush(record);
        work(today, ShiftType.WORK); work(today.plusDays(1), ShiftType.WORK);
        mvc.perform(get("/api/driver/leave-requests/makeup-candidates").header("Authorization", driverToken)
                .param("from", today.minusDays(6).toString()).param("to", today.plusDays(1).toString()))
                .andExpect(status().isOk()).andExpect(content().json("[\"" + system + "\",\"" + checked.getWorkDate() + "\",\"" + available + "\"]"));
    }

    @Test void 多日補請主管可逐日同意拒絕且每次必填回覆原因() throws Exception {
        LocalDate first = today.minusDays(3), second = today.minusDays(2);
        work(first, ShiftType.WORK); work(second, ShiftType.WORK);
        String result = mvc.perform(post("/api/driver/leave-requests/makeup-batch").header("Authorization", driverToken)
                .contentType(MediaType.APPLICATION_JSON).content(payload(List.of(first, second))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2)).andReturn().getResponse().getContentAsString();
        Number firstId = JsonPath.read(result, "$[0].id"), secondId = JsonPath.read(result, "$[1].id");
        mvc.perform(patch("/api/leave-requests/" + firstId + "/approve").header("Authorization", adminToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\" \"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(patch("/api/leave-requests/" + secondId + "/reject").header("Authorization", adminToken)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(patch("/api/leave-requests/" + firstId + "/approve").header("Authorization", adminToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"已確認就醫，准假\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.decisionReason").value("已確認就醫，准假"));
        mvc.perform(patch("/api/leave-requests/" + secondId + "/reject").header("Authorization", adminToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"請補充第二天說明\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.decisionReason").value("請補充第二天說明"));
        mvc.perform(get("/api/driver/leave-requests").header("Authorization", driverToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        assertEquals(ShiftType.WORK, shifts.findByDriverIdAndWorkDate(driverId, first).orElseThrow().getShiftType());
    }

    @Test void 任一補請日期已有打卡整批不留下申請() throws Exception {
        LocalDate first = today.minusDays(3), second = today.minusDays(2);
        work(first, ShiftType.WORK); DriverShiftsEntity checked = work(second, ShiftType.WORK);
        AttendanceRecordsEntity record = new AttendanceRecordsEntity();
        record.setDriverId(driverId); record.setDriverShiftId(checked.getId()); record.setWorkDate(second);
        record.setClockInAt(second.atTime(8, 0)); attendance.saveAndFlush(record);
        mvc.perform(post("/api/driver/leave-requests/makeup-batch").header("Authorization", driverToken)
                .contentType(MediaType.APPLICATION_JSON).content(payload(List.of(first, second))))
                .andExpect(status().isBadRequest());
        assertTrue(requests.findByDriverIdOrderByRequestedAtDesc(driverId).isEmpty());
    }

    @Test void 日期範圍與角色權限不能繞過() throws Exception {
        mvc.perform(post("/api/driver/leave-requests/makeup-batch").header("Authorization", driverToken)
                .contentType(MediaType.APPLICATION_JSON).content(payload(List.of(today))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/driver/leave-requests/makeup-batch").header("Authorization", adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(payload(List.of(today.minusDays(1)))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/driver/leave-requests/makeup-candidates").header("Authorization", driverToken)
                .param("from", today.minusDays(100).toString()).param("to", today.toString()))
                .andExpect(status().isBadRequest());
    }

    @Test void 已打卡可補過去遲到時段主管核准保留班表打卡與遲到分鐘() throws Exception {
        LocalDate date = today.minusDays(2);
        DriverShiftsEntity shift = work(date, ShiftType.WORK);
        AttendanceRecordsEntity record = new AttendanceRecordsEntity();
        record.setDriverId(driverId); record.setDriverShiftId(shift.getId()); record.setWorkDate(date);
        record.setClockInAt(date.atTime(9, 0)); record.setLateMinutes(60); record.setLeaveRequiredMinutes(60);
        record.setLeaveRequired(true); record.setPunctualityStatus(AttendancePunctualityStatus.LEAVE_REQUIRED);
        attendance.saveAndFlush(record);
        String result = mvc.perform(post("/api/driver/leave-requests/makeup-batch").header("Authorization", driverToken)
                .contentType(MediaType.APPLICATION_JSON).content(payload(List.of(date)).replace("}", ",\"leaveStart\":\"08:00\",\"leaveEnd\":\"09:00\"}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].fullDay").value(false))
                .andExpect(jsonPath("$[0].leaveStart").value("08:00:00"))
                .andExpect(jsonPath("$[0].leaveEnd").value("09:00:00")).andReturn().getResponse().getContentAsString();
        Number id = JsonPath.read(result, "$[0].id");
        mvc.perform(patch("/api/leave-requests/" + id + "/approve").header("Authorization", adminToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"已確認早上就醫，准假\"}"))
                .andExpect(status().isOk());
        assertEquals(ShiftType.WORK, shifts.findById(shift.getId()).orElseThrow().getShiftType());
        AttendanceRecordsEntity saved = attendance.findById(record.getId()).orElseThrow();
        assertEquals(date.atTime(9, 0), saved.getClockInAt()); assertEquals(60, saved.getLateMinutes());
        assertFalse(saved.getLeaveRequired()); assertEquals(id.longValue(), saved.getCoveredLeaveRequestId());
        assertEquals(AttendancePunctualityStatus.LEAVE_COVERED, saved.getPunctualityStatus());
    }

    @Test void 候選時段檢查班次與核准重疊但待審同日不可複選() throws Exception {
        LocalDate date = today.minusDays(2);
        DriverShiftsEntity shift = work(date, ShiftType.WORK);
        DriverLeaveRequestsEntity approved = new DriverLeaveRequestsEntity();
        approved.setDriverId(driverId); approved.setDriverShiftId(shift.getId()); approved.setWorkDate(date);
        approved.setRequestedLeaveType(LeaveType.SICK); approved.setLeaveType(LeaveType.SICK);
        approved.setFullDay(false); approved.setLeaveStart(LocalTime.of(8, 0)); approved.setLeaveEnd(LocalTime.of(9, 0));
        approved.setRequestReason("早上就醫"); approved.setStatus(LeaveRequestStatus.APPROVED);
        approved.setRequestMode(LeaveRequestMode.MAKEUP); requests.saveAndFlush(approved);
        for (String[] period : List.of(new String[]{"08:30", "10:00"}, new String[]{"07:00", "08:00"}, new String[]{"16:00", "18:00"})) {
            mvc.perform(get("/api/driver/leave-requests/makeup-candidates").header("Authorization", driverToken)
                    .param("from", date.toString()).param("to", date.toString())
                    .param("leaveStart", period[0]).param("leaveEnd", period[1]))
                    .andExpect(status().isOk()).andExpect(content().json("[]"));
        }
        mvc.perform(get("/api/driver/leave-requests/makeup-candidates").header("Authorization", driverToken)
                .param("from", date.toString()).param("to", date.toString()).param("leaveStart", "09:00").param("leaveEnd", "10:00"))
                .andExpect(status().isOk()).andExpect(content().json("[\"" + date + "\"]"));
        approved.setStatus(LeaveRequestStatus.PENDING); requests.saveAndFlush(approved);
        mvc.perform(get("/api/driver/leave-requests/makeup-candidates").header("Authorization", driverToken)
                .param("from", date.toString()).param("to", date.toString()).param("leaveStart", "13:00").param("leaveEnd", "17:00"))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
    }

    @Test void 多日補請時段超過任一班次整批回滾且單邊時間不可提交() throws Exception {
        LocalDate first = today.minusDays(3), second = today.minusDays(2);
        work(first, ShiftType.WORK); DriverShiftsEntity shorter = work(second, ShiftType.WORK);
        shorter.setWorkEnd(LocalTime.of(16, 0)); shifts.saveAndFlush(shorter);
        String body = payload(List.of(first, second));
        mvc.perform(post("/api/driver/leave-requests/makeup-batch").header("Authorization", driverToken)
                .contentType(MediaType.APPLICATION_JSON).content(body.replace("}", ",\"leaveStart\":\"13:00\",\"leaveEnd\":\"17:00\"}")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/driver/leave-requests/makeup-batch").header("Authorization", driverToken)
                .contentType(MediaType.APPLICATION_JSON).content(body.replace("}", ",\"leaveStart\":\"13:00\"}")))
                .andExpect(status().isBadRequest());
        assertTrue(requests.findByDriverIdOrderByRequestedAtDesc(driverId).isEmpty());
        mvc.perform(get("/api/driver/leave-requests/makeup-candidates").header("Authorization", driverToken)
                .param("from", first.toString()).param("to", second.toString()).param("leaveStart", "13:00"))
                .andExpect(status().isBadRequest());
    }

    @Test void 臨請核准保留上班而預排核准才改班表() throws Exception {
        work(today, ShiftType.WORK);
        String temporary = mvc.perform(post("/api/driver/leave-requests").header("Authorization", driverToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"workDate\":\"" + today + "\",\"leaveType\":\"SICK\",\"reason\":\"今天發燒\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Number temporaryId = JsonPath.read(temporary, "$.id");
        mvc.perform(patch("/api/leave-requests/" + temporaryId + "/approve").header("Authorization", adminToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"同意當日臨請\"}"))
                .andExpect(status().isOk());
        assertEquals(ShiftType.WORK, shifts.findByDriverIdAndWorkDate(driverId, today).orElseThrow().getShiftType());
        LocalDate future = today.plusDays(1);
        while (future.getDayOfWeek() == DayOfWeek.SUNDAY) future = future.plusDays(1);
        work(future, ShiftType.WORK);
        String planned = mvc.perform(post("/api/driver/leave-requests/planned-batches").header("Authorization", driverToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"groups\":[{\"workDates\":[\"" + future + "\"],\"leaveType\":\"ANNUAL\",\"reason\":\"家庭安排\"}]}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String batchId = JsonPath.read(planned, "$[0].batchId");
        assertEquals(ShiftType.WORK, shifts.findByDriverIdAndWorkDate(driverId, future).orElseThrow().getShiftType());
        mvc.perform(patch("/api/leave-requests/batches/" + batchId + "/approve").header("Authorization", adminToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"代班已安排，核准預排\"}"))
                .andExpect(status().isOk());
        assertEquals(ShiftType.LEAVE, shifts.findByDriverIdAndWorkDate(driverId, future).orElseThrow().getShiftType());
    }

    String token(String role, Long id) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer(AuthService.TOKEN_ISSUER).subject("leave-calendar-test")
                .issuedAt(now).expiresAt(now.plusSeconds(300)).claim("userId", id).claim("role", role).build();
        return "Bearer " + encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }
}
