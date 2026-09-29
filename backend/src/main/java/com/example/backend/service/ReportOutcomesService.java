package com.example.backend.service;

import com.example.backend.constants.ExceptionType;
import com.example.backend.constants.OrderStatus;
import com.example.backend.constants.OrderType;
import com.example.backend.constants.RouteStatus;
import com.example.backend.dao.ReportReadDAO;
import com.example.backend.dao.StoresDAO;
import com.example.backend.dao.WarehousesDAO;
import com.example.backend.dto.respones.ReportOutcomesResponse;
import com.example.backend.dto.respones.ReportOutcomesResponse.*;
import com.example.backend.entity.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** All warehouses share the same denominators; unknown evidence is never counted as success. */
@Service
@Transactional(readOnly = true)
public class ReportOutcomesService {
    private final ReportReadDAO reads;
    private final StoresDAO stores;
    private final WarehousesDAO warehouses;

    public ReportOutcomesService(ReportReadDAO reads, StoresDAO stores, WarehousesDAO warehouses) {
        this.reads = reads; this.stores = stores; this.warehouses = warehouses;
    }

    public ReportOutcomesResponse outcomes(ReportService.Range range, Long warehouseId) {
        return outcomes(range, warehouseId, false);
    }
    public ReportOutcomesResponse outcomes(ReportService.Range range, Long warehouseId, boolean includeDetails) {
        return outcomes(range, warehouseId, (Long) null, includeDetails);
    }

    public ReportOutcomesResponse outcomes(ReportService.Range range, Long warehouseId,
            Long vehicleId, boolean includeDetails) {
        return outcomesForVehicles(range, warehouseId, vehicleId == null ? null : Set.of(vehicleId),
                LocalDateTime.now(ZoneId.of("Asia/Taipei")), includeDetails);
    }

    public ReportOutcomesResponse outcomesForVehicles(ReportService.Range range, Long warehouseId,
            Set<Long> vehicleIds, boolean includeDetails) {
        return outcomesForVehicles(range, warehouseId, vehicleIds,
                LocalDateTime.now(ZoneId.of("Asia/Taipei")), includeDetails);
    }

    // Explicit as-of time makes cutoff and overnight-window behavior deterministic in tests.
    ReportOutcomesResponse outcomes(ReportService.Range range, Long warehouseId, LocalDateTime now) {
        return outcomes(range, warehouseId, now, false);
    }
    ReportOutcomesResponse outcomes(ReportService.Range range, Long warehouseId, LocalDateTime now, boolean includeDetails) {
        return outcomes(range, warehouseId, null, now, includeDetails);
    }

    ReportOutcomesResponse outcomes(ReportService.Range range, Long warehouseId,
            Long vehicleId, LocalDateTime now, boolean includeDetails) {
        return outcomesForVehicles(range, warehouseId, vehicleId == null ? null : Set.of(vehicleId), now, includeDetails);
    }

    ReportOutcomesResponse outcomesForVehicles(ReportService.Range range, Long warehouseId,
            Set<Long> vehicleIds, LocalDateTime now, boolean includeDetails) {
        Map<Long, StoresEntity> byStore = stores.findAll().stream()
                .collect(Collectors.toMap(StoresEntity::getId, Function.identity()));
        List<OrdersEntity> allOrders = vehicleIds == null
                ? reads.orders(range.getFrom(), range.getTo(), warehouseId)
                : vehicleIds.size() == 1
                ? reads.orders(range.getFrom(), range.getTo(), warehouseId, vehicleIds.iterator().next())
                : reads.ordersForVehicles(range.getFrom(), range.getTo(), warehouseId, vehicleIds);
        List<OrdersEntity> orders = allOrders.stream()
                .filter(o -> o.getStatus() != OrderStatus.CANCELLED && o.getStatus() != OrderStatus.PENDING_CONFIRM)
                .toList();
        List<Long> ids = orders.stream().map(OrdersEntity::getId).toList();
        Map<Long, DeliveryRecordsEntity> deliveries = new HashMap<>();
        for (var record : reads.deliveriesForOrders(ids)) {
            if (!observed(record, now)) continue;
            deliveries.merge(record.getOrderId(), record,
                    (a, b) -> a.getId() >= b.getId() ? a : b);
        }
        // A follow-up's source may fall outside the selected period; preserve that recorded cause.
        var evidenceIds = new LinkedHashSet<>(ids);
        orders.stream().map(OrdersEntity::getParentOrderId).filter(Objects::nonNull).forEach(evidenceIds::add);
        var allCases = reads.exceptionsForOrders(new ArrayList<>(evidenceIds)).stream()
                .filter(e -> past(e.getCreatedAt(), now)).toList();
        var recoveryReasons = new HashMap<Long, String>();
        allCases.forEach(e -> {
            if (e.getFollowUpOrderId() != null) recoveryReasons.put(e.getFollowUpOrderId(), recoveryReason(e.getType())
                    + (e.getDescription() == null || e.getDescription().isBlank() ? "" : "：" + e.getDescription()));
        });
        var loadingCases = allCases.stream()
                .filter(e -> e.getType() == ExceptionType.LOADING_MISMATCH && past(e.getCreatedAt(), now))
                .toList();
        Set<Long> mismatches = loadingCases.stream().map(ExceptionCasesEntity::getOrderId).collect(Collectors.toSet());
        Map<Long, String> loadingIssues = new HashMap<>();
        loadingCases.forEach(e -> loadingIssues.put(e.getOrderId(), e.getDescription() == null ? "點交不符" : e.getDescription()));
        List<Observation> observations = orders.stream()
                .map(o -> observe(o, byStore.get(o.getStoreId()), deliveries.get(o.getId()), mismatches, now)).toList();
        // Pending-confirmation orders are excluded from delivery KPIs, but an overdue one
        // still belongs in the incident drill-down. Do not silently lose it from the chart.
        List<Observation> overduePendingDetails = includeDetails ? allOrders.stream()
                .filter(o -> o.getStatus() == OrderStatus.PENDING_CONFIRM)
                .map(o -> observe(o, byStore.get(o.getStoreId()), null, mismatches, now))
                .filter(Observation::due).toList() : List.of();
        List<WarehouseOutcome> byWarehouse = new ArrayList<>();
        var directory = warehouses.findAll().stream().sorted(Comparator.comparing(WarehousesEntity::getId)).toList();
        var names = directory.stream().collect(Collectors.toMap(WarehousesEntity::getId, WarehousesEntity::getName));
        for (var warehouse : directory) {
            if (warehouseId != null && !warehouseId.equals(warehouse.getId())) continue;
            var rows = observations.stream().filter(o -> warehouse.getId().equals(o.order().getWarehouseId())).toList();
            if (vehicleIds != null && rows.isEmpty()) continue;
            byWarehouse.add(new WarehouseOutcome(warehouse.getId(), warehouse.getName(), delivery(rows), loading(rows), problems(rows)));
        }
        Set<Long> knownWarehouses = byWarehouse.stream().map(WarehouseOutcome::warehouseId).collect(Collectors.toSet());
        var unknown = observations.stream().filter(o -> !knownWarehouses.contains(o.order().getWarehouseId())).toList();
        if (warehouseId == null && !unknown.isEmpty())
            byWarehouse.add(new WarehouseOutcome(null, "未歸屬倉庫", delivery(unknown), loading(unknown), problems(unknown)));
        var byDay = observations.stream().filter(Observation::due)
                .collect(Collectors.groupingBy(o -> o.order().getDeliveryDate(), TreeMap::new, Collectors.toList()));
        List<DeliveryDay> daily = byDay.entrySet().stream().map(e ->
                new DeliveryDay(e.getKey(), e.getValue().size(), count(e.getValue(), Observation::full))).toList();
        var allRoutes = vehicleIds == null
                ? reads.routes(range.getFrom(), range.getTo(), warehouseId)
                : vehicleIds.size() == 1
                ? reads.routes(range.getFrom(), range.getTo(), warehouseId, vehicleIds.iterator().next())
                : reads.routesForVehicles(range.getFrom(), range.getTo(), warehouseId, vehicleIds);
        var driverByRoute = new HashMap<Long, Long>();
        allRoutes.forEach(r -> { if (r.getDriverId() != null) driverByRoute.put(r.getId(), r.getDriverId()); });
        List<Long> detailIds = includeDetails ? java.util.stream.Stream.concat(ids.stream(),
                overduePendingDetails.stream().map(o -> o.order().getId())).toList() : List.of();
        Map<Long, List<OrderItemsEntity>> itemsByOrder = includeDetails
                ? reads.orderItemsForOrders(detailIds).stream().collect(Collectors.groupingBy(i -> i.getOrder().getId())) : Map.of();
        var routes = allRoutes.stream()
                .filter(r -> r.getStatus() == RouteStatus.PUBLISHED && !r.getDate().isAfter(now.toLocalDate())).toList();
        return new ReportOutcomesResponse(range.getFrom(), range.getTo(), now, delivery(observations),
                safety(routes, reads.inspections(range.getFrom(), range.getTo()), now),
                loading(observations), problems(observations), byWarehouse, daily, recovery(observations, deliveries),
                includeDetails ? java.util.stream.Stream.concat(observations.stream(), overduePendingDetails.stream()).map(o -> detail(o, deliveries.get(o.order().getId()),
                        byStore.get(o.order().getStoreId()), names, loadingIssues, recoveryReasons, driverByRoute,
                        itemsByOrder.getOrDefault(o.order().getId(), List.of()))).toList() : List.of());
    }

    private static OrderOutcome detail(Observation o, DeliveryRecordsEntity record, StoresEntity store,
            Map<Long, String> names, Map<Long, String> loadingIssues, Map<Long, String> recoveryReasons, Map<Long, Long> driverByRoute,
            List<OrderItemsEntity> orderItems) {
        var order = o.order(); var w = window(order.getDeliveryDate(), store);
        var items = orderItems.stream().map(i -> new ItemCheck(i.getProductCode(), i.getItemName(),
                i.getExpectedQuantity(), i.getLoadedQuantity(), i.getUnit(), i.getLoadingNotes(),
                i.isLoadingMismatchReported())).toList();
        return new OrderOutcome(order.getId(), order.getOrderNumber(), order.getDeliveryDate(), order.getWarehouseId(),
                names.getOrDefault(order.getWarehouseId(), "未歸屬倉庫"), order.getStoreId(), store == null ? "未設定門市" : store.getName(),
                order.getAssignedDriverId() != null ? order.getAssignedDriverId() : driverByRoute.get(order.getRouteId()),
                order.getStatus().name(), o.due(), o.delivered(), o.full(), o.withinWindow(),
                o.late(), o.early(), o.missingArrival(), o.missingQuality(), o.assessed(), o.shortage(), o.damaged(),
                o.noSignature(), o.loaded() && !o.mismatch(), o.mismatch(), o.missingLoading(),
                o.due() && order.getStatus() == OrderStatus.CONFIRMED && order.getRouteId() == null,
                w == null ? null : w.start(), w == null ? null : w.end(), record == null ? null : record.getArrivedAt(),
                record == null ? null : record.getDeliveredAt(), order.getLoadedAt(), order.getBoxCount(), record == null ? null : record.getExpectedBoxCount(),
                record == null ? null : record.getDeliveredBoxCount(), record == null ? null : record.getShortageBoxCount(),
                record == null ? null : record.getDamagedBoxCount(), record == null ? null : record.getReplacementRequiredBoxCount(),
                loadingIssues.get(order.getId()), order.getLoadingNotes(), items, order.getOrderType() == null ? null : order.getOrderType().name(),
                order.getParentOrderId(), record != null, isRecovery(order), recoveryReasons.get(order.getId()));
    }

    private static boolean isRecovery(OrdersEntity order) {
        return order.getParentOrderId() != null && (order.getOrderType() == OrderType.REDELIVERY || order.getOrderType() == OrderType.REPLENISHMENT);
    }

    private static Recovery recovery(List<Observation> rows, Map<Long, DeliveryRecordsEntity> records) {
        int attempted = count(rows, o -> records.containsKey(o.order().getId()));
        var followUps = rows.stream().filter(o -> isRecovery(o.order())).toList();
        int executed = count(followUps, o -> records.containsKey(o.order().getId()));
        int delivered = count(followUps, Observation::delivered);
        return new Recovery(attempted, followUps.size(), executed, delivered, followUps.size() - delivered,
                rate(executed, attempted));
    }

    private static String recoveryReason(ExceptionType type) {
        if (type == null) return "補送原因未記錄";
        return switch (type) {
            case NO_SIGNATURE -> "無人簽收重送";
            case LOADING_MISMATCH -> "出貨點交不符重送";
            case UNSETTLED_ORDER -> "逾日未結重送";
            case SHORTAGE -> "短少補送";
            case DAMAGE -> "破損補送";
            case SHORTAGE_AND_DAMAGE -> "短少與破損補送";
            default -> "其他已記錄異常";
        };
    }

    private record Window(LocalDateTime start, LocalDateTime end) {}
    private record Observation(OrdersEntity order, boolean due, boolean windowKnown, boolean delivered,
            boolean full, boolean withinWindow, boolean late, boolean early, boolean missingArrival,
            boolean missingQuality, boolean assessed, boolean shortage, boolean damaged, boolean noSignature,
            boolean loaded, boolean mismatch, boolean missingLoading) {}

    private static Observation observe(OrdersEntity order, StoresEntity store, DeliveryRecordsEntity record,
            Set<Long> mismatches, LocalDateTime now) {
        Window window = window(order.getDeliveryDate(), store);
        // No explicit promised deadline exists: use current receiving hours, or end of day if unknown.
        LocalDateTime cutoff = window == null ? order.getDeliveryDate().plusDays(1).atStartOfDay() : window.end();
        boolean due = now.isAfter(cutoff);
        boolean delivered = record != null && Boolean.FALSE.equals(record.getNoSignature())
                && past(record.getDeliveredAt(), now)
                && (record.getArrivedAt() == null || !record.getDeliveredAt().isBefore(record.getArrivedAt()));
        boolean knownQuality = delivered && validCounts(record);
        boolean full = knownQuality && record.getDeliveredBoxCount().equals(record.getExpectedBoxCount())
                && record.getShortageBoxCount() == 0 && record.getDamagedBoxCount() == 0
                && record.getReplacementRequiredBoxCount() == 0;
        LocalDateTime arrival = record == null ? null : record.getArrivedAt();
        boolean validArrival = past(arrival, now);
        boolean noSignature = record != null && Boolean.TRUE.equals(record.getNoSignature())
                && past(record.getHandledAt(), now);
        boolean loaded = past(order.getLoadedAt(), now);
        boolean mismatch = mismatches.contains(order.getId());
        return new Observation(order, due, window != null, delivered, full,
                window != null && validArrival && !arrival.isBefore(window.start()) && !arrival.isAfter(window.end()),
                window != null && validArrival && arrival.isAfter(window.end()),
                window != null && validArrival && arrival.isBefore(window.start()),
                window != null && !validArrival, delivered && !knownQuality, knownQuality || noSignature,
                knownQuality && positive(record.getShortageBoxCount()), knownQuality && positive(record.getDamagedBoxCount()),
                noSignature, loaded, mismatch, (delivered || noSignature) && !loaded && !mismatch);
    }

    private static Window window(LocalDate date, StoresEntity store) {
        if (store == null || store.getReceivingStart() == null || store.getReceivingEnd() == null
                || store.getReceivingStart().equals(store.getReceivingEnd())) return null;
        LocalDateTime start = date.atTime(store.getReceivingStart()), end = date.atTime(store.getReceivingEnd());
        if (end.isBefore(start)) end = end.plusDays(1);
        return new Window(start, end);
    }

    private static boolean validCounts(DeliveryRecordsEntity r) {
        Integer expected = r.getExpectedBoxCount(), delivered = r.getDeliveredBoxCount(), shortage = r.getShortageBoxCount(),
                damage = r.getDamagedBoxCount(), replacement = r.getReplacementRequiredBoxCount();
        return expected != null && expected > 0 && delivered != null && delivered >= 0
                && shortage != null && shortage >= 0 && (long) delivered + shortage == expected
                && damage != null && damage >= 0 && damage <= delivered
                && replacement != null && replacement >= 0 && replacement <= (long) shortage + damage;
    }

    private static boolean observed(DeliveryRecordsEntity r, LocalDateTime now) {
        return past(r.getArrivedAt(), now) || past(r.getDeliveredAt(), now) || past(r.getHandledAt(), now);
    }
    private static boolean past(LocalDateTime time, LocalDateTime now) { return time != null && !time.isAfter(now); }
    private static boolean positive(Integer value) { return value != null && value > 0; }
    private static int count(List<Observation> rows, java.util.function.Predicate<Observation> predicate) {
        return (int) rows.stream().filter(predicate).count();
    }
    private static Double rate(int numerator, int denominator) { return denominator == 0 ? null : numerator * 100.0 / denominator; }

    private static Delivery delivery(List<Observation> observations) {
        var rows = observations.stream().filter(Observation::due).toList();
        int delivered = count(rows, Observation::delivered), full = count(rows, Observation::full);
        int eligible = count(rows, Observation::windowKnown), within = count(rows, Observation::withinWindow);
        return new Delivery(rows.size(), delivered, full, rows.size() - delivered, eligible, within,
                count(rows, Observation::late), count(rows, Observation::early), count(rows, Observation::missingArrival),
                count(rows, o -> !o.windowKnown()), count(rows, Observation::missingQuality),
                rate(full, rows.size()), rate(within, eligible));
    }

    private static Loading loading(List<Observation> observations) {
        // Loading has its own event evidence and does not wait until the store's receiving window closes.
        int matched = count(observations, o -> o.loaded() && !o.mismatch()), mismatch = count(observations, Observation::mismatch);
        return new Loading(matched + mismatch, matched, mismatch, count(observations, Observation::missingLoading),
                count(observations, o -> o.due() && o.order().getStatus() == OrderStatus.CONFIRMED && o.order().getRouteId() == null),
                rate(matched, matched + mismatch));
    }

    private static Problems problems(List<Observation> observations) {
        var rows = observations.stream().filter(Observation::due).toList();
        int assessed = count(rows, Observation::assessed);
        int affected = count(rows, o -> o.assessed() && (o.shortage() || o.damaged() || o.noSignature()));
        return new Problems(assessed, affected, count(rows, Observation::shortage), count(rows, Observation::damaged),
                count(rows, Observation::noSignature), rate(affected, assessed));
    }

    private static Safety safety(List<RoutesEntity> routes, List<PreTripInspectionsEntity> records, LocalDateTime now) {
        Map<Long, PreTripInspectionsEntity> latest = new HashMap<>();
        Map<Long, RoutesEntity> byId = routes.stream().collect(Collectors.toMap(RoutesEntity::getId, Function.identity()));
        for (var record : records) {
            var route = byId.get(record.getRouteId());
            if (route == null || !past(record.getSubmittedAt(), now) || record.getInvalidatedAt() != null
                    || !Objects.equals(record.getWorkDate(), route.getDate())
                    || !Objects.equals(record.getDriverId(), route.getDriverId())
                    || !Objects.equals(record.getVehicleId(), route.getVehicleId())
                    || !Objects.equals(record.getRouteVersion(), route.getVersion())) continue;
            latest.merge(record.getRouteId(), record, (a, b) -> a.getId() >= b.getId() ? a : b);
        }
        int passed = (int) latest.values().stream().filter(r -> Boolean.TRUE.equals(r.getPassed())).count();
        return new Safety(routes.size(), latest.size(), passed, latest.size() - passed,
                routes.size() - latest.size(), rate(passed, latest.size()));
    }
}
