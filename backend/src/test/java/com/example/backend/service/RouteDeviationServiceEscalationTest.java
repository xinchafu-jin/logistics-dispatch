package com.example.backend.service;

import com.example.backend.constants.AttendanceStatus;
import com.example.backend.constants.RouteDeviationEndReason;
import com.example.backend.constants.RouteDeviationPushType;
import com.example.backend.dao.AttendanceRecordsDAO;
import com.example.backend.dao.MileageLogsDAO;
import com.example.backend.dao.OrdersDAO;
import com.example.backend.dao.RouteDeviationsDAO;
import com.example.backend.dao.RoutePlannedLegsDAO;
import com.example.backend.dao.RoutesDAO;
import com.example.backend.dto.respones.RouteDeviationPushResponse;
import com.example.backend.entity.AttendanceRecordsEntity;
import com.example.backend.entity.MileageLogsEntity;
import com.example.backend.entity.RouteDeviationsEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 每分鐘的排程（escalateOverdue）：滿 10 分鐘升級成警報；休息、收車、下班、跨日就結束那一筆，不升級。
 *
 * <p>DAO 全部是 mock：這裡只驗規則，資料表讀寫由 RouteDeviationsDAOTest 負責。</p>
 */
class RouteDeviationServiceEscalationTest {

    private static final long DRIVER_ID = 7L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 28, 10, 30);
    private static final LocalDate TODAY = NOW.toLocalDate();

    private MileageLogsDAO mileageLogsDAO;
    private AttendanceRecordsDAO attendanceRecordsDAO;
    private RouteDeviationsDAO routeDeviationsDAO;
    private ApplicationEventPublisher eventPublisher;
    private RouteDeviationService routeDeviationService;

    @BeforeEach
    void setUp() {
        mileageLogsDAO = mock(MileageLogsDAO.class);
        attendanceRecordsDAO = mock(AttendanceRecordsDAO.class);
        routeDeviationsDAO = mock(RouteDeviationsDAO.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        when(routeDeviationsDAO.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        routeDeviationService = new RouteDeviationService(
                mock(RoutesDAO.class), mock(OrdersDAO.class), mock(RoutePlannedLegsDAO.class),
                mileageLogsDAO, attendanceRecordsDAO, routeDeviationsDAO,
                JsonMapper.builder().build(), eventPublisher);
    }

    @Test
    void 偏離剛好滿10分鐘_還在出車上班中_升級成警報並推播() {
        RouteDeviationsEntity deviation = givenOpenDeviation(NOW.minusMinutes(10));
        givenTrip(TODAY, null);
        givenAttendance(TODAY, AttendanceStatus.WORKING);

        routeDeviationService.escalateOverdue(NOW);

        assertEquals(NOW, deviation.getEscalatedAt());
        assertNull(deviation.getEndedAt(), "升級不是結束，後台還要繼續顯示");
        RouteDeviationPushResponse push = onlyPush();
        assertEquals(RouteDeviationPushType.ESCALATED, push.getType());
        assertEquals(NOW, push.getDeviation().getEscalatedAt());
    }

    @Test
    void 偏離還差1秒才滿10分鐘_不升級() {
        RouteDeviationsEntity deviation = givenOpenDeviation(NOW.minusMinutes(10).plusSeconds(1));
        givenTrip(TODAY, null);
        givenAttendance(TODAY, AttendanceStatus.WORKING);

        routeDeviationService.escalateOverdue(NOW);

        assertNull(deviation.getEscalatedAt());
        verify(routeDeviationsDAO, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void 已經升級過_不會再推一次() {
        RouteDeviationsEntity deviation = givenOpenDeviation(NOW.minusMinutes(30));
        deviation.setEscalatedAt(NOW.minusMinutes(20));
        givenTrip(TODAY, null);
        givenAttendance(TODAY, AttendanceStatus.OVERTIME);

        routeDeviationService.escalateOverdue(NOW);

        assertEquals(NOW.minusMinutes(20), deviation.getEscalatedAt(), "升級時間要留第一次的");
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void 開始休息_結束那一筆_結束時間是開始休息的時間_不會升級() {
        RouteDeviationsEntity deviation = givenOpenDeviation(NOW.minusMinutes(20));
        givenTrip(TODAY, null);
        AttendanceRecordsEntity attendance = givenAttendance(TODAY, AttendanceStatus.ON_BREAK);
        attendance.setBreakStartedAt(NOW.minusMinutes(5));

        routeDeviationService.escalateOverdue(NOW);

        assertEquals(RouteDeviationEndReason.ON_BREAK, deviation.getEndReason());
        assertEquals(NOW.minusMinutes(5), deviation.getEndedAt(), "要用實際開始休息的時間，不是排程跑到的時間");
        assertNull(deviation.getEscalatedAt(), "休息不發警報");
        assertEquals(RouteDeviationPushType.ENDED, onlyPush().getType());
    }

    @Test
    void 已收車_結束那一筆_結束時間是收車時間() {
        RouteDeviationsEntity deviation = givenOpenDeviation(NOW.minusMinutes(20));
        givenTrip(TODAY, NOW.minusMinutes(3));
        givenAttendance(TODAY, AttendanceStatus.WORKING);

        routeDeviationService.escalateOverdue(NOW);

        assertEquals(RouteDeviationEndReason.TRIP_ENDED, deviation.getEndReason());
        assertEquals(NOW.minusMinutes(3), deviation.getEndedAt());
        assertEquals(RouteDeviationPushType.ENDED, onlyPush().getType());
    }

    @Test
    void 已下班_結束那一筆_結束時間是打下班卡的時間() {
        RouteDeviationsEntity deviation = givenOpenDeviation(NOW.minusMinutes(20));
        givenTrip(TODAY, null);
        AttendanceRecordsEntity attendance = givenAttendance(TODAY, AttendanceStatus.CLOCKED_OUT);
        attendance.setClockOutAt(NOW.minusMinutes(1));

        routeDeviationService.escalateOverdue(NOW);

        assertEquals(RouteDeviationEndReason.OFF_DUTY, deviation.getEndReason());
        assertEquals(NOW.minusMinutes(1), deviation.getEndedAt());
    }

    @Test
    void 前一天的偏離到現在還開著_結束在那天最後一秒() {
        LocalDate yesterday = TODAY.minusDays(1);
        RouteDeviationsEntity deviation = givenOpenDeviation(yesterday.atTime(16, 0));
        // 手機沒電又忘了收車、打下班卡：前一天的出車與出勤都還停在進行中
        givenTrip(yesterday, null);
        givenAttendance(yesterday, AttendanceStatus.WORKING);

        routeDeviationService.escalateOverdue(NOW);

        assertEquals(RouteDeviationEndReason.OFF_DUTY, deviation.getEndReason());
        assertEquals(yesterday.atTime(23, 59, 59), deviation.getEndedAt());
        assertEquals(RouteDeviationPushType.ENDED, onlyPush().getType(), "只結束，不會順便升級");
    }

    @Test
    void 休息時間資料不一致_早於偏離開始_結束時間用偏離開始的時間() {
        RouteDeviationsEntity deviation = givenOpenDeviation(NOW.minusMinutes(20));
        givenTrip(TODAY, null);
        AttendanceRecordsEntity attendance = givenAttendance(TODAY, AttendanceStatus.ON_BREAK);
        attendance.setBreakStartedAt(NOW.minusHours(3));

        routeDeviationService.escalateOverdue(NOW);

        assertEquals(NOW.minusMinutes(20), deviation.getEndedAt(), "結束不能早於開始，報表算時長才不會是負的");
    }

    private RouteDeviationsEntity givenOpenDeviation(LocalDateTime startedAt) {
        RouteDeviationsEntity deviation = new RouteDeviationsEntity();
        deviation.setId(99L);
        deviation.setRouteId(11L);
        deviation.setDriverId(DRIVER_ID);
        deviation.setLegSequence(2);
        deviation.setStartedAt(startedAt);
        deviation.setStartLat(22.6273);
        deviation.setStartLng(120.3014);
        deviation.setStartDistanceMeters(236.5);
        when(routeDeviationsDAO.findAllByEndedAtIsNullOrderByStartedAtAsc()).thenReturn(List.of(deviation));
        return deviation;
    }

    /** 出車紀錄：早上 8 點出車；endTime 是 null 代表還沒收車 */
    private void givenTrip(LocalDate date, LocalDateTime endTime) {
        MileageLogsEntity trip = new MileageLogsEntity();
        trip.setStartTime(date.atTime(8, 0));
        trip.setEndTime(endTime);
        when(mileageLogsDAO.findByDriverIdAndDate(DRIVER_ID, date)).thenReturn(Optional.of(trip));
    }

    private AttendanceRecordsEntity givenAttendance(LocalDate date, AttendanceStatus status) {
        AttendanceRecordsEntity attendance = new AttendanceRecordsEntity();
        attendance.setStatus(status);
        when(attendanceRecordsDAO.findByDriverIdAndWorkDate(DRIVER_ID, date)).thenReturn(Optional.of(attendance));
        return attendance;
    }

    /** 這一輪只推了一則，回傳那一則 */
    private RouteDeviationPushResponse onlyPush() {
        ArgumentCaptor<RouteDeviationPushResponse> captor = ArgumentCaptor.forClass(RouteDeviationPushResponse.class);
        verify(eventPublisher).publishEvent(captor.capture());
        return captor.getValue();
    }
}
