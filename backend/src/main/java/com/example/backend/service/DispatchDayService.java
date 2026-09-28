package com.example.backend.service;

import com.example.backend.constants.DispatchDayStatus;
import com.example.backend.constants.OrderStatus;
import com.example.backend.constants.RouteStatus;
import com.example.backend.dao.OrdersDAO;
import com.example.backend.dao.RoutesDAO;
import com.example.backend.dto.respones.DispatchDayResponse;
import com.example.backend.entity.OrdersEntity;
import com.example.backend.entity.RoutesEntity;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * 看板日期列：每一天全部倉庫合起來的狀態與數量。
 *
 * <p>範圍是 from～to 每一天，加上 from 之前 {@value #UNRESOLVED_LOOKBACK_DAYS} 天內還沒結案的日子，
 * 已結案的舊日子不列（改到歷史紀錄查）。整段路線與訂單各查一次，再在記憶體裡按日期分組。</p>
 */
@Service
public class DispatchDayService {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    /**
     * 一次最多查幾天，避免前端一次拉一整年
     */
    static final int MAX_RANGE_DAYS = 31;

    /**
     * 沒指定 to 時至少顯示到 from 後幾天，保證「今天、明天、後天」看得到
     */
    static final int MIN_DEFAULT_DAYS_AHEAD = 2;

    /**
     * 往前找未結案日子的天數；更久以前還沒結案是資料問題，交給報表
     */
    static final int UNRESOLVED_LOOKBACK_DAYS = 30;

    /**
     * 還沒結束的訂單。待確認也算：補送單沒人確認，那天就不能算結案
     */
    static final Set<OrderStatus> UNFINISHED_STATUSES = EnumSet.of(
            OrderStatus.PENDING_CONFIRM, OrderStatus.CONFIRMED,
            OrderStatus.LOADED, OrderStatus.IN_DELIVERY);

    /**
     * 已結束的訂單。取消的單另外排除，不算進任何數量
     */
    static final Set<OrderStatus> FINISHED_STATUSES = EnumSet.of(
            OrderStatus.COMPLETED, OrderStatus.NO_SIGNATURE, OrderStatus.FAILED);

    private final OrdersDAO ordersDAO;
    private final RoutesDAO routesDAO;

    public DispatchDayService(OrdersDAO ordersDAO, RoutesDAO routesDAO) {
        this.ordersDAO = ordersDAO;
        this.routesDAO = routesDAO;
    }

    public List<DispatchDayResponse> getDays(LocalDate from, LocalDate to) {
        return getDays(from, to, LocalDate.now(TAIPEI));
    }

    /**
     * today 由外面傳進來，測試才能固定「今天」是哪一天
     */
    List<DispatchDayResponse> getDays(LocalDate from, LocalDate to, LocalDate today) {
        LocalDate start = from != null ? from : today;
        LocalDate end = to != null ? to : defaultEnd(start);
        if (end.isBefore(start)) {
            throw new IllegalArgumentException("結束日期不能早於開始日期");
        }
        if (ChronoUnit.DAYS.between(start, end) + 1 > MAX_RANGE_DAYS) {
            throw new IllegalArgumentException("一次最多查詢 " + MAX_RANGE_DAYS + " 天");
        }

        List<LocalDate> unresolvedDates = ordersDAO.findDeliveryDatesWithStatus(
                start.minusDays(UNRESOLVED_LOOKBACK_DAYS), start, UNFINISHED_STATUSES);

        List<OrdersEntity> orders = new ArrayList<>(ordersDAO.findByDeliveryDateBetween(start, end));
        List<RoutesEntity> routes = new ArrayList<>(routesDAO.findByDateBetween(start, end));
        if (!unresolvedDates.isEmpty()) {
            orders.addAll(ordersDAO.findByDeliveryDateIn(unresolvedDates));
            routes.addAll(routesDAO.findByDateIn(unresolvedDates));
        }

        Map<LocalDate, List<OrdersEntity>> ordersByDate = new HashMap<>();
        for (OrdersEntity order : orders) {
            if (order.getStatus() == OrderStatus.CANCELLED) {
                continue;
            }
            ordersByDate.computeIfAbsent(order.getDeliveryDate(), date -> new ArrayList<>()).add(order);
        }
        Map<LocalDate, List<RoutesEntity>> routesByDate = new HashMap<>();
        for (RoutesEntity route : routes) {
            routesByDate.computeIfAbsent(route.getDate(), date -> new ArrayList<>()).add(route);
        }

        // TreeSet：未結案的舊日子和區間內的日子合在一起，依日期排好
        Set<LocalDate> dates = new TreeSet<>(unresolvedDates);
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            dates.add(date);
        }

        List<DispatchDayResponse> days = new ArrayList<>();
        for (LocalDate date : dates) {
            List<OrdersEntity> dayOrders = ordersByDate.getOrDefault(date, List.of());
            List<RoutesEntity> dayRoutes = routesByDate.getOrDefault(date, List.of());
            days.add(toResponse(date, today, dayRoutes, dayOrders));
        }
        return days;
    }

    /**
     * 沒指定 to：顯示到最後一天有單的日期，至少到後天，最多 MAX_RANGE_DAYS 天
     */
    private LocalDate defaultEnd(LocalDate start) {
        LocalDate end = start.plusDays(MIN_DEFAULT_DAYS_AHEAD);
        LocalDate latest = ordersDAO.findLatestDeliveryDate(start, OrderStatus.CANCELLED);
        if (latest != null && latest.isAfter(end)) {
            end = latest;
        }
        LocalDate cap = start.plusDays(MAX_RANGE_DAYS - 1);
        if (end.isAfter(cap)) {
            end = cap;
        }
        return end;
    }

    private DispatchDayResponse toResponse(
            LocalDate date, LocalDate today, List<RoutesEntity> routes, List<OrdersEntity> orders) {
        int pendingConfirm = 0;
        int unassigned = 0;
        int finished = 0;
        for (OrdersEntity order : orders) {
            if (order.getStatus() == OrderStatus.PENDING_CONFIRM) {
                pendingConfirm++;
            } else if (order.getStatus() == OrderStatus.CONFIRMED && order.getRouteId() == null) {
                unassigned++;
            } else if (FINISHED_STATUSES.contains(order.getStatus())) {
                finished++;
            }
        }

        DispatchDayResponse response = new DispatchDayResponse();
        response.setDate(date);
        response.setStatus(resolveStatus(date, today, routes, orders));
        response.setPublished(routes.stream().anyMatch(route -> route.getStatus() == RouteStatus.PUBLISHED));
        response.setOrderCount(orders.size());
        response.setPendingConfirmCount(pendingConfirm);
        response.setUnassignedCount(unassigned);
        response.setFinishedCount(finished);
        return response;
    }

    /**
     * 判斷這一天的狀態，依序先符合的先算：
     * <ol>
     *   <li>沒有任何單 → EMPTY</li>
     *   <li>全部單都已結束 → CLOSED</li>
     *   <li>日期已過（date 早於 today），還有單沒結束 → UNRESOLVED</li>
     *   <li>有單已點交、配送中，或已經有單結束 → IN_PROGRESS</li>
     *   <li>有路線、全部都是 PUBLISHED → PUBLISHED</li>
     *   <li>有路線，其中有 DRAFT → DRAFT</li>
     *   <li>有單沒路線 → UNPLANNED</li>
     * </ol>
     *
     * @param orders 這一天全部倉庫的訂單，取消的已經排除
     * @param routes 這一天全部倉庫的路線。注意：可能有沒排任何單的空路線（看板也會略過它們），
     *               「有路線」要不要算它們由你決定
     */
    private DispatchDayStatus resolveStatus(
            LocalDate date, LocalDate today, List<RoutesEntity> routes, List<OrdersEntity> orders) {
        if (orders.isEmpty()) {
            return DispatchDayStatus.EMPTY;
        }
        boolean allFinished = true;
        for (OrdersEntity order : orders) {
            if (!FINISHED_STATUSES.contains(order.getStatus())) {
                allFinished = false;
                break;
            }
        }
        if (allFinished) {
            return DispatchDayStatus.CLOSED;
        }

        if (date.isBefore(today)) {
            return DispatchDayStatus.UNRESOLVED;
        }
        for (OrdersEntity order : orders) {
            OrderStatus status = order.getStatus();
            if (status == OrderStatus.LOADED
                    || status == OrderStatus.IN_DELIVERY
                    || FINISHED_STATUSES.contains(status)
            ) {
                return DispatchDayStatus.IN_PROGRESS;
            }
        }
        Set<Long> useRoute = new HashSet<>();
        for (OrdersEntity order : orders) {
            if (order.getRouteId() != null) {
                useRoute.add(order.getRouteId());
            }
        }
        boolean hasRoute = false;
        boolean allPublished = true;
        for (RoutesEntity route : routes) {
            if (!useRoute.contains(route.getId())) {
                continue;
            }
            hasRoute = true;
            if (route.getStatus() != RouteStatus.PUBLISHED) {
                allPublished = false;
            }
        }
        if (hasRoute && allPublished) {
            return DispatchDayStatus.PUBLISHED;
        }
        if (hasRoute) {
            return DispatchDayStatus.DRAFT;
        }
        return DispatchDayStatus.UNPLANNED;
    }
}
