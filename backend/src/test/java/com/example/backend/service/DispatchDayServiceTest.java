package com.example.backend.service;

import com.example.backend.constants.DispatchDayStatus;
import com.example.backend.constants.OrderStatus;
import com.example.backend.constants.RouteStatus;
import com.example.backend.dao.OrdersDAO;
import com.example.backend.dao.RoutesDAO;
import com.example.backend.dto.respones.DispatchDayResponse;
import com.example.backend.entity.OrdersEntity;
import com.example.backend.entity.RoutesEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 看板日期列：每一天的狀態判斷、數量、查詢範圍。
 *
 * <p>今天固定是 9/25。每個測試把訂單和路線放進 orders、routes，再查單一天或一段區間。</p>
 */
class DispatchDayServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 25);
    private static final LocalDate YESTERDAY = TODAY.minusDays(1);

    private final List<OrdersEntity> orders = new ArrayList<>();
    private final List<RoutesEntity> routes = new ArrayList<>();
    private final List<LocalDate> unresolvedDates = new ArrayList<>();
    private OrdersDAO ordersDAO;
    private DispatchDayService service;
    private long nextOrderId = 1;

    @BeforeEach
    void setUp() {
        ordersDAO = mock(OrdersDAO.class);
        RoutesDAO routesDAO = mock(RoutesDAO.class);
        // 假 DAO 照日期篩 orders、routes，模擬資料庫只回傳區間內的資料
        when(ordersDAO.findByDeliveryDateBetween(any(), any())).thenAnswer(invocation -> {
            LocalDate from = invocation.getArgument(0);
            LocalDate to = invocation.getArgument(1);
            return orders.stream()
                    .filter(order -> !order.getDeliveryDate().isBefore(from) && !order.getDeliveryDate().isAfter(to))
                    .toList();
        });
        when(ordersDAO.findByDeliveryDateIn(any())).thenAnswer(invocation -> {
            List<LocalDate> dates = invocation.getArgument(0);
            return orders.stream().filter(order -> dates.contains(order.getDeliveryDate())).toList();
        });
        when(routesDAO.findByDateBetween(any(), any())).thenAnswer(invocation -> {
            LocalDate from = invocation.getArgument(0);
            LocalDate to = invocation.getArgument(1);
            return routes.stream()
                    .filter(route -> !route.getDate().isBefore(from) && !route.getDate().isAfter(to))
                    .toList();
        });
        when(routesDAO.findByDateIn(any())).thenAnswer(invocation -> {
            List<LocalDate> dates = invocation.getArgument(0);
            return routes.stream().filter(route -> dates.contains(route.getDate())).toList();
        });
        when(ordersDAO.findDeliveryDatesWithStatus(any(), any(), any())).thenReturn(unresolvedDates);
        service = new DispatchDayService(ordersDAO, routesDAO);
    }

    // ── 七種狀態 ─────────────────────────────────────────

    @Test
    void 沒有單_只有取消的單也算沒有() {
        order(TODAY, OrderStatus.CANCELLED, null);

        assertEquals(DispatchDayStatus.EMPTY, statusOf(TODAY));
    }

    @Test
    void 全部結束_已結案() {
        order(TODAY, OrderStatus.COMPLETED, 10L);
        order(TODAY, OrderStatus.NO_SIGNATURE, 10L);
        order(TODAY, OrderStatus.FAILED, 10L);
        route(10L, TODAY, RouteStatus.PUBLISHED);

        assertEquals(DispatchDayStatus.CLOSED, statusOf(TODAY));
    }

    @Test
    void 第一張送完_後面還在跑_不能算結案() {
        order(TODAY, OrderStatus.COMPLETED, 10L);
        order(TODAY, OrderStatus.IN_DELIVERY, 10L);
        route(10L, TODAY, RouteStatus.PUBLISHED);

        assertEquals(DispatchDayStatus.IN_PROGRESS, statusOf(TODAY));
    }

    @Test
    void 日期已過還有單沒結束_未結案() {
        order(YESTERDAY, OrderStatus.IN_DELIVERY, 10L);
        route(10L, YESTERDAY, RouteStatus.PUBLISHED);

        assertEquals(DispatchDayStatus.UNRESOLVED, statusOf(YESTERDAY));
    }

    @Test
    void 有單點交了_配送中() {
        order(TODAY, OrderStatus.LOADED, 10L);
        order(TODAY, OrderStatus.CONFIRMED, 11L);
        route(10L, TODAY, RouteStatus.PUBLISHED);
        route(11L, TODAY, RouteStatus.PUBLISHED);

        assertEquals(DispatchDayStatus.IN_PROGRESS, statusOf(TODAY));
    }

    @Test
    void 路線全部發布_還沒人動_已發布() {
        order(TODAY, OrderStatus.CONFIRMED, 10L);
        route(10L, TODAY, RouteStatus.PUBLISHED);

        assertEquals(DispatchDayStatus.PUBLISHED, statusOf(TODAY));
    }

    @Test
    void 有一條還是草稿_草稿() {
        order(TODAY, OrderStatus.CONFIRMED, 10L);
        order(TODAY, OrderStatus.CONFIRMED, 11L);
        route(10L, TODAY, RouteStatus.PUBLISHED);
        route(11L, TODAY, RouteStatus.DRAFT);

        assertEquals(DispatchDayStatus.DRAFT, statusOf(TODAY));
    }

    @Test
    void 有單沒路線_未排() {
        order(TODAY, OrderStatus.CONFIRMED, null);
        order(TODAY, OrderStatus.PENDING_CONFIRM, null);

        assertEquals(DispatchDayStatus.UNPLANNED, statusOf(TODAY));
    }

    @Test
    void 空的草稿路線不算有路線() {
        order(TODAY, OrderStatus.CONFIRMED, null);
        route(10L, TODAY, RouteStatus.DRAFT);

        assertEquals(DispatchDayStatus.UNPLANNED, statusOf(TODAY));
    }

    @Test
    void 撤回後仍有已完成訂單_配送進度不代表已發布() {
        order(TODAY, OrderStatus.COMPLETED, 10L);
        order(TODAY, OrderStatus.COMPLETED, 10L);
        order(TODAY, OrderStatus.COMPLETED, 10L);
        order(TODAY, OrderStatus.CONFIRMED, 10L);
        route(10L, TODAY, RouteStatus.DRAFT);

        DispatchDayResponse day = service.getDays(TODAY, TODAY, TODAY).get(0);

        assertEquals(DispatchDayStatus.IN_PROGRESS, day.getStatus());
        assertEquals(3, day.getFinishedCount());
        assertFalse(day.isPublished());
        routes.get(0).setStatus(RouteStatus.PUBLISHED);
        DispatchDayResponse republished = service.getDays(TODAY, TODAY, TODAY).get(0);
        assertEquals(DispatchDayStatus.IN_PROGRESS, republished.getStatus());
        assertTrue(republished.isPublished());
    }

    @Test
    void 已結案且已撤回_不可視為已發布() {
        order(TODAY, OrderStatus.COMPLETED, 10L);
        route(10L, TODAY, RouteStatus.DRAFT);

        DispatchDayResponse day = service.getDays(TODAY, TODAY, TODAY).get(0);
        assertEquals(DispatchDayStatus.CLOSED, day.getStatus());
        assertFalse(day.isPublished());
    }

    @Test
    void 未結案舊日期且已撤回_不可視為已發布() {
        order(YESTERDAY, OrderStatus.CONFIRMED, 10L);
        route(10L, YESTERDAY, RouteStatus.DRAFT);

        DispatchDayResponse day = service.getDays(YESTERDAY, YESTERDAY, TODAY).get(0);
        assertEquals(DispatchDayStatus.UNRESOLVED, day.getStatus());
        assertFalse(day.isPublished());
    }

    @Test
    void 另一個倉庫仍有已發布路線_必須顯示已發布() {
        order(TODAY, OrderStatus.CONFIRMED, 10L);
        order(TODAY, OrderStatus.COMPLETED, 11L);
        route(10L, TODAY, RouteStatus.DRAFT);
        routes.get(0).setWarehouseId(1L);
        route(11L, TODAY, RouteStatus.PUBLISHED);
        routes.get(1).setWarehouseId(2L);

        DispatchDayResponse day = service.getDays(TODAY, TODAY, TODAY).get(0);
        assertEquals(DispatchDayStatus.IN_PROGRESS, day.getStatus());
        assertTrue(day.isPublished());
    }

    // ── 數量 ─────────────────────────────────────────────

    @Test
    void 各種數量_取消的不算() {
        order(TODAY, OrderStatus.PENDING_CONFIRM, null);
        order(TODAY, OrderStatus.CONFIRMED, null);
        order(TODAY, OrderStatus.CONFIRMED, 10L);
        order(TODAY, OrderStatus.COMPLETED, 10L);
        order(TODAY, OrderStatus.CANCELLED, null);
        route(10L, TODAY, RouteStatus.PUBLISHED);

        DispatchDayResponse day = service.getDays(TODAY, TODAY, TODAY).get(0);

        assertEquals(4, day.getOrderCount());
        assertEquals(1, day.getPendingConfirmCount());
        assertEquals(1, day.getUnassignedCount());
        assertEquals(1, day.getFinishedCount());
    }

    // ── 查詢範圍 ─────────────────────────────────────────

    @Test
    void 區間前面沒結案的日子排在最前面() {
        LocalDate oldDay = TODAY.minusDays(3);
        unresolvedDates.add(oldDay);
        order(oldDay, OrderStatus.PENDING_CONFIRM, null);

        List<DispatchDayResponse> days = service.getDays(TODAY, TODAY.plusDays(1), TODAY);

        assertEquals(List.of(oldDay, TODAY, TODAY.plusDays(1)),
                days.stream().map(DispatchDayResponse::getDate).toList());
        assertEquals(DispatchDayStatus.UNRESOLVED, days.get(0).getStatus());
    }

    @Test
    void 沒給範圍_至少顯示到七天後() {
        when(ordersDAO.findLatestDeliveryDate(eq(TODAY), any())).thenReturn(null);

        List<DispatchDayResponse> days = service.getDays(null, null, TODAY);

        assertEquals(8, days.size());
        assertEquals(TODAY, days.get(0).getDate());
        assertEquals(TODAY.plusDays(7), days.get(7).getDate());
    }

    @Test
    void 沒給範圍_顯示到最後一天有單的日期() {
        when(ordersDAO.findLatestDeliveryDate(eq(TODAY), any())).thenReturn(TODAY.plusDays(9));

        List<DispatchDayResponse> days = service.getDays(null, null, TODAY);

        assertEquals(10, days.size());
    }

    @Test
    void 沒給範圍_最多三十一天() {
        when(ordersDAO.findLatestDeliveryDate(eq(TODAY), any())).thenReturn(TODAY.plusDays(100));

        List<DispatchDayResponse> days = service.getDays(null, null, TODAY);

        assertEquals(DispatchDayService.MAX_RANGE_DAYS, days.size());
    }

    @Test
    void 自己指定超過三十一天_擋下() {
        assertThrows(IllegalArgumentException.class,
                () -> service.getDays(TODAY, TODAY.plusDays(31), TODAY));
    }

    @Test
    void 結束早於開始_擋下() {
        assertThrows(IllegalArgumentException.class,
                () -> service.getDays(TODAY, YESTERDAY, TODAY));
    }

    // ── 測試資料 ─────────────────────────────────────────

    private DispatchDayStatus statusOf(LocalDate date) {
        return service.getDays(date, date, TODAY).get(0).getStatus();
    }

    private void order(LocalDate date, OrderStatus status, Long routeId) {
        OrdersEntity order = new OrdersEntity();
        order.setId(nextOrderId++);
        order.setDeliveryDate(date);
        order.setStatus(status);
        order.setRouteId(routeId);
        orders.add(order);
    }

    private void route(Long id, LocalDate date, RouteStatus status) {
        RoutesEntity route = new RoutesEntity();
        route.setId(id);
        route.setDate(date);
        route.setStatus(status);
        routes.add(route);
    }
}
