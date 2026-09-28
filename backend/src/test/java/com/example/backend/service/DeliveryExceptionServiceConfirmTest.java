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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DeliveryExceptionServiceConfirmTest {
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private ExceptionCasesDAO cases;
    private OrdersDAO orders;
    private RoutesDAO routes;
    private DeliveryExceptionService service;
    private OrdersEntity source;
    private OrdersEntity rebuilt;
    private ExceptionCasesEntity incident;
    private LocalDate today;

    @BeforeEach
    void setUp() {
        today = LocalDate.now(TAIPEI);
        cases = mock(ExceptionCasesDAO.class);
        orders = mock(OrdersDAO.class);
        routes = mock(RoutesDAO.class);
        service = new DeliveryExceptionService(cases, mock(DeliveryRecordsDAO.class), orders, routes);
        source = new OrdersEntity();
        source.setId(1L);
        source.setDeliveryDate(today.minusDays(2));
        source.setStatus(OrderStatus.FAILED);
        source.setRouteId(10L);
        rebuilt = new OrdersEntity();
        rebuilt.setId(2L);
        rebuilt.setWarehouseId(3L);
        rebuilt.setStatus(OrderStatus.PENDING_CONFIRM);
        rebuilt.setDeliveryDate(today.plusDays(1));
        incident = new ExceptionCasesEntity();
        incident.setId(7L);
        incident.setOrderId(source.getId());
        incident.setFollowUpOrderId(rebuilt.getId());
        incident.setStatus(ExceptionStatus.OPEN);
        incident.setCreatedAt(LocalDateTime.now(TAIPEI).minusDays(2));
        incident.setReviewAvailableAt(LocalDateTime.now(TAIPEI).minusMinutes(1));
        when(cases.findForUpdate(7L)).thenReturn(Optional.of(incident));
        when(orders.findForUpdate(2L)).thenReturn(Optional.of(rebuilt));
        when(orders.findById(1L)).thenReturn(Optional.of(source));
        when(orders.findById(2L)).thenReturn(Optional.of(rebuilt));
    }

    @ParameterizedTest
    @EnumSource(value = ExceptionType.class, names = "NO_SIGNATURE", mode = EnumSource.Mode.EXCLUDE)
    void 所有非無人簽收異常_確認後改為今天待排且保留原單歷史(ExceptionType type) {
        incident.setType(type);
        var response = service.confirm(7L, "主管");
        assertEquals(today, rebuilt.getDeliveryDate());
        assertEquals(OrderStatus.CONFIRMED, rebuilt.getStatus());
        assertNull(rebuilt.getRouteId());
        assertNull(rebuilt.getAssignedDriverId());
        assertNull(rebuilt.getAssignedVehicleId());
        assertEquals(today, response.getFollowUpDeliveryDate());
        assertEquals(ExceptionStatus.CLOSED, incident.getStatus());
        assertEquals(today.minusDays(2), source.getDeliveryDate());
        assertEquals(OrderStatus.FAILED, source.getStatus());
        assertEquals(10L, source.getRouteId());
        verify(orders).save(rebuilt);
        // 已发布路線不影響新單的配送日期，但也不在此改寫既有路線。
        verifyNoInteractions(routes);
        verify(orders, never()).save(source);
    }

    @Test
    void 已過期但尚未指派的已確認異常重建單_也更新成今天() {
        incident.setType(ExceptionType.LOADING_MISMATCH);
        rebuilt.setStatus(OrderStatus.CONFIRMED);
        rebuilt.setDeliveryDate(today.minusDays(1));
        service.confirm(7L, "主管");
        assertEquals(today, rebuilt.getDeliveryDate());
    }

    @Test
    void 無人簽收_保留自動隔日配送日期() {
        incident.setType(ExceptionType.NO_SIGNATURE);
        service.confirm(7L, "主管");
        assertEquals(today.plusDays(1), rebuilt.getDeliveryDate());
        assertEquals(OrderStatus.CONFIRMED, rebuilt.getStatus());
        verify(routes).findByDateAndWarehouseId(today.plusDays(1), 3L);
    }

    @Test
    void 無人簽收_仍避開已發布而不能重排的日期() {
        incident.setType(ExceptionType.NO_SIGNATURE);
        RoutesEntity published = new RoutesEntity();
        published.setStatus(RouteStatus.PUBLISHED);
        when(routes.findByDateAndWarehouseId(today.plusDays(1), 3L)).thenReturn(List.of(published));
        service.confirm(7L, "主管");
        assertEquals(today.plusDays(2), rebuilt.getDeliveryDate());
    }

    @Test
    void 已排車的異常重建單_不擅自改日期或路線() {
        incident.setType(ExceptionType.SHORTAGE);
        rebuilt.setStatus(OrderStatus.CONFIRMED);
        rebuilt.setRouteId(20L);
        assertThrows(IllegalArgumentException.class, () -> service.confirm(7L, "主管"));
        assertEquals(today.plusDays(1), rebuilt.getDeliveryDate());
        assertEquals(20L, rebuilt.getRouteId());
        assertEquals(ExceptionStatus.OPEN, incident.getStatus());
        verify(orders, never()).save(any());
        verify(cases, never()).save(any());
    }

    @Test
    void 已結案的異常不能重複建立或確認訂單() {
        incident.setStatus(ExceptionStatus.CLOSED);
        assertThrows(IllegalArgumentException.class, () -> service.confirm(7L, "主管"));
        verify(orders, never()).findForUpdate(any());
        verify(orders, never()).save(any());
    }
}
