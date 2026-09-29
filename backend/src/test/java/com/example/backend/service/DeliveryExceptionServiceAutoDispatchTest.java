package com.example.backend.service;

import com.example.backend.constants.ExceptionStatus;
import com.example.backend.constants.ExceptionType;
import com.example.backend.constants.OrderStatus;
import com.example.backend.constants.RouteStatus;
import com.example.backend.dao.DeliveryRecordsDAO;
import com.example.backend.dao.ExceptionCasesDAO;
import com.example.backend.dao.OrdersDAO;
import com.example.backend.dao.RoutesDAO;
import com.example.backend.entity.ExceptionCasesEntity;
import com.example.backend.entity.OrdersEntity;
import com.example.backend.entity.RoutesEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DeliveryExceptionServiceAutoDispatchTest {
    private static final LocalDateTime SIX_AM = LocalDateTime.of(2026, 9, 30, 6, 0);

    private ExceptionCasesDAO cases;
    private OrdersDAO orders;
    private RoutesDAO routes;
    private DispatchBoardPushService boardPush;
    private DeliveryExceptionService service;
    private ExceptionCasesEntity incident;
    private OrdersEntity followUp;

    @BeforeEach
    void setUp() {
        cases = mock(ExceptionCasesDAO.class);
        orders = mock(OrdersDAO.class);
        routes = mock(RoutesDAO.class);
        boardPush = mock(DispatchBoardPushService.class);
        service = new DeliveryExceptionService(
                cases, mock(DeliveryRecordsDAO.class), orders, routes, boardPush);

        incident = new ExceptionCasesEntity();
        incident.setId(7L);
        incident.setType(ExceptionType.NO_SIGNATURE);
        incident.setStatus(ExceptionStatus.OPEN);
        incident.setFollowUpOrderId(2L);
        incident.setReviewAvailableAt(SIX_AM);
        followUp = new OrdersEntity();
        followUp.setId(2L);
        followUp.setWarehouseId(3L);
        followUp.setDeliveryDate(SIX_AM.toLocalDate());
        followUp.setStatus(OrderStatus.PENDING_CONFIRM);
        when(orders.findForUpdate(2L)).thenReturn(Optional.of(followUp));
    }

    private void dueAt(LocalDateTime now) {
        when(cases.findDueNoSignatureForUpdate(
                ExceptionStatus.OPEN, ExceptionType.NO_SIGNATURE, now))
                .thenReturn(List.of(incident));
        when(cases.findDueForUpdate(ExceptionStatus.OPEN, now, ExceptionType.NO_SIGNATURE))
                .thenReturn(List.of());
    }

    @Test
    void 隔日六點自動進當天待排車_案件記錄由系統結案() {
        dueAt(SIX_AM);

        service.queueDueCases(SIX_AM);

        assertEquals(OrderStatus.CONFIRMED, followUp.getStatus());
        assertEquals(SIX_AM.toLocalDate(), followUp.getDeliveryDate());
        assertEquals(ExceptionStatus.CLOSED, incident.getStatus());
        assertEquals(SIX_AM, incident.getQueuedAt());
        assertEquals(SIX_AM, incident.getHandledAt());
        assertEquals("系統自動送待排", incident.getHandledBy());
        assertTrue(incident.getResolution().contains("2026-09-30"));
        verify(orders).save(followUp);
        verify(cases).save(incident);
        verifyNoInteractions(boardPush);
    }

    @Test
    void 六點前即使查到案件也不提前送單() {
        LocalDateTime early = SIX_AM.minusMinutes(1);
        dueAt(early);

        service.queueDueCases(early);

        assertEquals(OrderStatus.PENDING_CONFIRM, followUp.getStatus());
        assertEquals(ExceptionStatus.OPEN, incident.getStatus());
        verify(orders, never()).save(any());
        verify(cases, never()).save(any());
    }

    @Test
    void 後端停機漏過六點_補掃時改到補掃當天並通知舊日期() {
        LocalDateTime catchUp = SIX_AM.plusDays(2);
        incident.setQueuedAt(SIX_AM.plusMinutes(5)); // 舊資料可能已進人工確認佇列，也必須自動補掃。
        dueAt(catchUp);

        service.queueDueCases(catchUp);

        assertEquals(LocalDate.of(2026, 10, 2), followUp.getDeliveryDate());
        assertEquals(OrderStatus.CONFIRMED, followUp.getStatus());
        assertEquals(SIX_AM.plusMinutes(5), incident.getQueuedAt());
        verify(boardPush).markChanged(LocalDate.of(2026, 9, 30));
    }

    @Test
    void 當天已發布時避開鎖定看板_不改寫既有路線() {
        dueAt(SIX_AM);
        RoutesEntity published = new RoutesEntity();
        published.setStatus(RouteStatus.PUBLISHED);
        when(routes.findByDateAndWarehouseId(SIX_AM.toLocalDate(), 3L))
                .thenReturn(List.of(published));

        service.queueDueCases(SIX_AM);

        assertEquals(LocalDate.of(2026, 10, 1), followUp.getDeliveryDate());
        verify(routes).findByDateAndWarehouseId(LocalDate.of(2026, 10, 1), 3L);
        verify(boardPush).markChanged(SIX_AM.toLocalDate());
    }

    @Test
    void 已結案重掃不會重複建單或變更日期() {
        incident.setStatus(ExceptionStatus.CLOSED);
        dueAt(SIX_AM.plusMinutes(1));

        service.queueDueCases(SIX_AM.plusMinutes(1));

        assertEquals(OrderStatus.PENDING_CONFIRM, followUp.getStatus());
        verify(orders, never()).findForUpdate(any());
        verify(orders, never()).save(any());
        verify(cases, never()).save(any());
    }

    @Test
    void 已有指派的舊重送單不被自動搬日期() {
        followUp.setRouteId(88L);
        dueAt(SIX_AM);

        service.queueDueCases(SIX_AM);

        assertEquals(SIX_AM.toLocalDate(), followUp.getDeliveryDate());
        assertEquals(ExceptionStatus.OPEN, incident.getStatus());
        verify(orders, never()).save(any());
        verify(cases, never()).save(any());
    }

    @Test
    void 缺來源訂單的舊案件不妨礙其他無人簽收自動送單() {
        ExceptionCasesEntity orphan = new ExceptionCasesEntity();
        orphan.setId(6L);
        orphan.setType(ExceptionType.NO_SIGNATURE);
        orphan.setStatus(ExceptionStatus.OPEN);
        orphan.setOrderId(999L);
        orphan.setReviewAvailableAt(SIX_AM.minusDays(1));
        when(orders.findForUpdate(999L)).thenReturn(Optional.empty());
        when(cases.findDueNoSignatureForUpdate(
                ExceptionStatus.OPEN, ExceptionType.NO_SIGNATURE, SIX_AM))
                .thenReturn(List.of(orphan, incident));

        service.queueDueCases(SIX_AM);

        assertEquals(ExceptionStatus.OPEN, orphan.getStatus());
        assertEquals(ExceptionStatus.CLOSED, incident.getStatus());
        assertEquals(OrderStatus.CONFIRMED, followUp.getStatus());
        verify(orders).save(followUp);
    }

    @Test
    void 其他異常仍進主管確認區_不自動送待排() {
        ExceptionCasesEntity shortage = new ExceptionCasesEntity();
        shortage.setId(8L);
        shortage.setType(ExceptionType.SHORTAGE);
        shortage.setStatus(ExceptionStatus.OPEN);
        when(cases.findDueForUpdate(ExceptionStatus.OPEN, SIX_AM, ExceptionType.NO_SIGNATURE))
                .thenReturn(List.of(shortage));

        service.queueDueCases(SIX_AM);

        assertEquals(SIX_AM, shortage.getQueuedAt());
        assertEquals(ExceptionStatus.OPEN, shortage.getStatus());
        verify(cases).saveAll(List.of(shortage));
        verify(orders, never()).save(any());
    }

    @Test
    void 六點前的無人簽收會留在異常中心顯示等待自動處理() {
        incident.setOrderId(null);
        when(cases.findByTypeAndStatusOrderByIdAsc(ExceptionType.NO_SIGNATURE, ExceptionStatus.OPEN))
                .thenReturn(List.of(incident));
        when(orders.findById(2L)).thenReturn(Optional.of(followUp));

        var pending = service.findPendingConfirmation();

        assertEquals(1, pending.size());
        assertEquals(ExceptionType.NO_SIGNATURE, pending.getFirst().getType());
        assertEquals(ExceptionStatus.OPEN, pending.getFirst().getStatus());
        assertEquals(SIX_AM.toLocalDate(), pending.getFirst().getFollowUpDeliveryDate());
    }

    @Test
    void 缺少重送單的舊示範案件不顯示成可自動排車() {
        incident.setFollowUpOrderId(null);
        when(cases.findByTypeAndStatusOrderByIdAsc(ExceptionType.NO_SIGNATURE, ExceptionStatus.OPEN))
                .thenReturn(List.of(incident));

        assertTrue(service.findPendingConfirmation().isEmpty());
    }
}
