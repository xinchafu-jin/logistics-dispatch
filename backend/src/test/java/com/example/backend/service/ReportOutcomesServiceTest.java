package com.example.backend.service;

import com.example.backend.constants.*;
import com.example.backend.dao.*;
import com.example.backend.entity.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReportOutcomesServiceTest {
    private final ReportReadDAO reads = mock(ReportReadDAO.class);
    private final StoresDAO stores = mock(StoresDAO.class);
    private final WarehousesDAO warehouses = mock(WarehousesDAO.class);
    private final ReportOutcomesService service = new ReportOutcomesService(reads, stores, warehouses);
    private final LocalDate day = LocalDate.of(2026, 9, 28);
    private final LocalDateTime now = day.atTime(19, 0);
    private final ReportService.Range range = new ReportService.Range(day.minusDays(1), day.plusDays(2));
    private final List<OrdersEntity> orders = new ArrayList<>();
    private final List<DeliveryRecordsEntity> deliveries = new ArrayList<>();
    private final List<ExceptionCasesEntity> cases = new ArrayList<>();
    private final List<RoutesEntity> routes = new ArrayList<>();
    private final List<PreTripInspectionsEntity> inspections = new ArrayList<>();
    private StoresEntity store;

    @BeforeEach void setup() {
        store = new StoresEntity(); store.setId(10L);
        store.setReceivingStart(LocalTime.of(9, 0)); store.setReceivingEnd(LocalTime.of(18, 0));
        when(stores.findAll()).thenReturn(List.of(store));
        var one = new WarehousesEntity(); one.setId(1L); one.setName("左營倉");
        var two = new WarehousesEntity(); two.setId(2L); two.setName("台南倉");
        var empty = new WarehousesEntity(); empty.setId(3L); empty.setName("空倉");
        when(warehouses.findAll()).thenReturn(List.of(one, two, empty));
        when(reads.orders(eq(range.getFrom()), eq(range.getTo()), any())).thenAnswer(call -> {
            Long id = call.getArgument(2);
            return orders.stream().filter(o -> id == null || id.equals(o.getWarehouseId())).toList();
        });
        when(reads.orders(eq(range.getFrom()), eq(range.getTo()), any(), anyLong())).thenAnswer(call -> {
            Long warehouseId = call.getArgument(2);
            Long vehicleId = call.getArgument(3);
            return orders.stream()
                    .filter(o -> warehouseId == null || warehouseId.equals(o.getWarehouseId()))
                    .filter(o -> vehicleId.equals(o.getAssignedVehicleId())).toList();
        });
        when(reads.ordersForVehicles(eq(range.getFrom()), eq(range.getTo()), any(), anySet())).thenAnswer(call -> {
            Long warehouseId = call.getArgument(2);
            Set<Long> vehicleIds = call.getArgument(3);
            return orders.stream()
                    .filter(o -> warehouseId == null || warehouseId.equals(o.getWarehouseId()))
                    .filter(o -> vehicleIds.contains(o.getAssignedVehicleId())).toList();
        });
        when(reads.deliveriesForOrders(anyList())).thenAnswer(call -> {
            List<Long> ids = call.getArgument(0); return deliveries.stream().filter(d -> ids.contains(d.getOrderId())).toList();
        });
        when(reads.exceptionsForOrders(anyList())).thenAnswer(call -> {
            List<Long> ids = call.getArgument(0); return cases.stream().filter(e -> ids.contains(e.getOrderId())).toList();
        });
        when(reads.routes(eq(range.getFrom()), eq(range.getTo()), any())).thenAnswer(call -> {
            Long id = call.getArgument(2); return routes.stream().filter(r -> id == null || id.equals(r.getWarehouseId())).toList();
        });
        when(reads.routes(eq(range.getFrom()), eq(range.getTo()), any(), anyLong())).thenAnswer(call -> {
            Long warehouseId = call.getArgument(2);
            Long vehicleId = call.getArgument(3);
            return routes.stream()
                    .filter(r -> warehouseId == null || warehouseId.equals(r.getWarehouseId()))
                    .filter(r -> vehicleId.equals(r.getVehicleId())).toList();
        });
        when(reads.routesForVehicles(eq(range.getFrom()), eq(range.getTo()), any(), anySet())).thenAnswer(call -> {
            Long warehouseId = call.getArgument(2);
            Set<Long> vehicleIds = call.getArgument(3);
            return routes.stream()
                    .filter(r -> warehouseId == null || warehouseId.equals(r.getWarehouseId()))
                    .filter(r -> vehicleIds.contains(r.getVehicleId())).toList();
        });
        when(reads.inspections(range.getFrom(), range.getTo())).thenReturn(inspections);
    }

    @Test void distinguishesCompletedStatusFromVerifiedFullDeliveryAndCountsEachOrderOnce() {
        var full = order(1, 1, day, OrderStatus.COMPLETED); deliver(full, 1, 10, 0, 0, 0, 17);
        var damaged = order(2, 1, day, OrderStatus.COMPLETED); deliver(damaged, 2, 8, 2, 1, 3, 18);
        var noSignature = order(3, 2, day, OrderStatus.NO_SIGNATURE); noSignature(noSignature, 3);
        order(4, 2, day, OrderStatus.CONFIRMED);
        order(5, 2, day, OrderStatus.CANCELLED); order(6, 2, day, OrderStatus.PENDING_CONFIRM);
        order(7, 2, day.plusDays(1), OrderStatus.CONFIRMED);
        var result = service.outcomes(range, null, now);
        assertEquals(4, result.delivery().dueOrders()); assertEquals(1, result.delivery().fullOrders());
        assertEquals(2, result.delivery().deliveredOrders()); assertEquals(2, result.delivery().outstandingOrders());
        assertEquals(25, result.delivery().fullDeliveryRate());
        assertEquals(3, result.problems().assessedOrders()); assertEquals(2, result.problems().affectedOrders());
        assertEquals(1, result.problems().shortageOrders()); assertEquals(1, result.problems().damagedOrders());
        assertEquals(1, result.problems().noSignatureOrders()); assertEquals(200.0 / 3, result.problems().issueRate(), .0001);
        assertEquals(1, result.loading().dueUnassignedOrders());
        assertEquals(3, result.warehouses().size()); assertNull(result.warehouses().getLast().delivery().fullDeliveryRate());
        assertEquals(4, result.daily().getFirst().dueOrders());
    }

    @Test void exposesOverduePendingConfirmationOnlyInDetailsWithoutChangingDeliveryKpis() {
        order(1, 1, day, OrderStatus.PENDING_CONFIRM);
        order(2, 1, day.plusDays(1), OrderStatus.PENDING_CONFIRM);
        var result = service.outcomes(range, null, now, true);
        assertEquals(0, result.delivery().dueOrders());
        assertEquals(1, result.orders().size());
        assertEquals(1L, result.orders().getFirst().orderId());
        assertTrue(result.orders().getFirst().due());
        assertEquals(OrderStatus.PENDING_CONFIRM.name(), result.orders().getFirst().status());
    }

    @Test void vehicleFilterAppliesToDeliveryDetailsAndWarehouseRows() {
        var selected = order(1, 1, day, OrderStatus.COMPLETED);
        selected.setAssignedVehicleId(30L);
        deliver(selected, 1, 10, 0, 0, 0, 17);
        var other = order(2, 2, day, OrderStatus.CONFIRMED);
        other.setAssignedVehicleId(40L);

        var result = service.outcomes(range, null, 30L, now, true);

        assertEquals(1, result.delivery().dueOrders());
        assertEquals(1, result.orders().size());
        assertEquals(1L, result.orders().getFirst().orderId());
        assertEquals(1, result.warehouses().size());
        assertEquals(1L, result.warehouses().getFirst().warehouseId());
        verify(reads).orders(range.getFrom(), range.getTo(), null, 30L);
        verify(reads).routes(range.getFrom(), range.getTo(), null, 30L);
    }

    @Test void tonnageGroupCombinesDeliveryOutcomesFromMultipleVehicles() {
        var first = order(1, 1, day, OrderStatus.COMPLETED);
        first.setAssignedVehicleId(30L);
        deliver(first, 1, 10, 0, 0, 0, 17);
        var second = order(2, 1, day, OrderStatus.CONFIRMED);
        second.setAssignedVehicleId(40L);
        var other = order(3, 2, day, OrderStatus.CONFIRMED);
        other.setAssignedVehicleId(50L);

        var result = service.outcomesForVehicles(range, null, Set.of(30L, 40L), now, true);

        assertEquals(2, result.delivery().dueOrders());
        assertEquals(List.of(1L, 2L), result.orders().stream().map(row -> row.orderId()).toList());
        assertEquals(1, result.warehouses().size());
    }

    @Test void comparesArrivalNotHandoffAndCountsMissingAndEarlyArrivalsSeparately() {
        var one = order(1, 1, day, OrderStatus.COMPLETED); var a = deliver(one, 1, 10, 0, 0, 0, 17);
        a.setDeliveredAt(day.atTime(18, 30)); // arrival is still on time
        var two = order(2, 1, day, OrderStatus.COMPLETED); deliver(two, 2, 10, 0, 0, 0, 19);
        var three = order(3, 1, day, OrderStatus.COMPLETED); deliver(three, 3, 10, 0, 0, 0, 8);
        order(4, 1, day, OrderStatus.CONFIRMED);
        var five = order(5, 1, day, OrderStatus.COMPLETED); deliver(five, 5, 10, 0, 0, 0, 18);
        var result = service.outcomes(range, null, now).delivery();
        assertEquals(5, result.windowEligibleOrders()); assertEquals(2, result.windowArrivals());
        assertEquals(1, result.lateArrivals()); assertEquals(1, result.earlyArrivals());
        assertEquals(1, result.missingArrivalOrders()); assertEquals(40, result.receivingWindowRate());
    }

    @Test void excludesNotYetDueAndHandlesOvernightCutoffs() {
        store.setReceivingStart(LocalTime.of(22, 0)); store.setReceivingEnd(LocalTime.of(2, 0));
        var previous = order(1, 1, day.minusDays(1), OrderStatus.COMPLETED);
        var record = deliver(previous, 1, 10, 0, 0, 0, 23);
        record.setArrivedAt(day.atTime(1, 0)); record.setDeliveredAt(day.atTime(1, 20));
        order(2, 1, day, OrderStatus.CONFIRMED);
        var delivery = service.outcomes(range, null, now).delivery();
        assertEquals(1, delivery.dueOrders()); assertEquals(1, delivery.fullOrders());
        assertEquals(100, delivery.receivingWindowRate()); assertEquals(0, delivery.lateArrivals());
    }

    @Test void doesNotGuessUnknownHoursQuantitiesOrMissingDeliveryEvidence() {
        var missing = order(1, 1, day.minusDays(1), OrderStatus.COMPLETED);
        missing.setStoreId(999L);
        var record = deliver(missing, 1, 10, 0, 0, 0, 17); record.setExpectedBoxCount(null);
        order(2, 1, day.minusDays(1), OrderStatus.COMPLETED); // status alone is not proof
        var result = service.outcomes(range, null, now).delivery();
        assertEquals(2, result.dueOrders()); assertEquals(0, result.fullOrders());
        assertEquals(1, result.missingQualityOrders()); assertEquals(1, result.missingWindowOrders());
        assertEquals(1, result.missingArrivalOrders()); assertEquals(1, result.outstandingOrders());
        assertNull(service.outcomes(range, null, now).problems().issueRate());
    }

    @Test void keepsSnapshotBoxCountAndUsesLatestAttemptWithoutDoubleCounting() {
        var order = order(1, 1, day, OrderStatus.COMPLETED);
        noSignature(order, 1); deliver(order, 2, 10, 0, 0, 0, 17);
        order.setBoxCount(99); // edited order must not overwrite delivery evidence
        var future = deliver(order, 3, 9, 1, 0, 0, 20);
        future.setArrivedAt(day.plusDays(1).atTime(12, 0)); future.setDeliveredAt(day.plusDays(1).atTime(12, 10));
        var result = service.outcomes(range, null, now);
        assertEquals(1, result.delivery().fullOrders()); assertEquals(0, result.problems().affectedOrders());
        assertEquals(0, result.problems().noSignatureOrders());
    }

    @Test void countsLoadingMismatchesFromEvidenceRatherThanAnyFailedOrder() {
        var matched = order(1, 1, day, OrderStatus.COMPLETED); matched.setLoadedAt(day.atTime(8, 0));
        var mismatch = order(2, 1, day, OrderStatus.FAILED); loadingMismatch(mismatch, 1);
        loadingMismatch(mismatch, 2); // repeated case must not inflate order denominator
        order(3, 1, day, OrderStatus.FAILED); // no loading evidence
        var legacy = order(4, 1, day, OrderStatus.COMPLETED); deliver(legacy, 4, 10, 0, 0, 0, 17);
        var result = service.outcomes(range, null, now).loading();
        assertEquals(2, result.checkedOrders()); assertEquals(1, result.matchedOrders());
        assertEquals(1, result.mismatchedOrders()); assertEquals(50, result.matchRate());
        assertEquals(1, result.missingLoadingOrders());
    }

    @Test void includesTheReportedLoadingProductWithoutInventingActualOrMissingQuantities() {
        var original = order(1, 1, day, OrderStatus.FAILED);
        loadingMismatch(original, 1);
        var item = new OrderItemsEntity(); item.setOrder(original); item.setProductCode("MILK");
        item.setItemName("鮮乳"); item.setExpectedQuantity(4); item.setUnit("箱");
        item.setLoadingMismatchReported(true);
        when(reads.orderItemsForOrders(anyList())).thenReturn(List.of(item));
        var row = service.outcomes(range, null, now, true).orders().getFirst();
        assertTrue(row.loadingMismatch());
        assertTrue(row.items().getFirst().loadingMismatchReported());
        assertEquals("鮮乳", row.items().getFirst().itemName());
        assertEquals(4, row.items().getFirst().expectedQuantity());
        assertNull(row.items().getFirst().loadedQuantity());
    }

    @Test void usesWeightedCompanyTotalsNotMeanWarehousePercentagesAndHonorsFilter() {
        for (int i = 1; i <= 9; i++) {
            var order = order(i, 1, day, OrderStatus.COMPLETED); deliver(order, i, 10, 0, 0, 0, 17);
        }
        order(10, 2, day, OrderStatus.CONFIRMED);
        var global = service.outcomes(range, null, now);
        assertEquals(90, global.delivery().fullDeliveryRate());
        assertEquals(100, global.warehouses().getFirst().delivery().fullDeliveryRate());
        assertEquals(0, global.warehouses().get(1).delivery().fullDeliveryRate());
        var filtered = service.outcomes(range, 2L, now);
        assertEquals(1, filtered.warehouses().size()); assertEquals(1, filtered.delivery().dueOrders());
        assertEquals(0, filtered.delivery().fullDeliveryRate());
    }

    @Test void requiresLatestInspectionToMatchTaskDriverVehicleVersionAndNotBeInvalidated() {
        for (long id = 1; id <= 6; id++) route(id, day);
        inspect(1, 1, true); inspect(2, 1, false); // newest check wins
        inspect(3, 2, true).setInvalidatedAt(now.minusHours(1));
        inspect(4, 3, true).setDriverId(999L);
        inspect(5, 4, true).setRouteVersion(2);
        inspect(6, 5, true).setVehicleId(999L);
        inspect(7, 6, true);
        route(7, day.plusDays(1)); inspect(8, 7, true); // future task excluded
        var result = service.outcomes(range, null, now).safety();
        assertEquals(6, result.assignedRoutes()); assertEquals(2, result.inspectedRoutes());
        assertEquals(1, result.passedRoutes()); assertEquals(1, result.failedRoutes());
        assertEquals(4, result.missingInspectionRoutes()); assertEquals(50, result.inspectionPassRate());
    }

    @Test void zeroDenominatorsRemainNullAndFutureOrdersDoNotLookLikeFailures() {
        order(1, 1, day.plusDays(1), OrderStatus.CONFIRMED);
        var result = service.outcomes(range, null, now);
        assertNull(result.delivery().fullDeliveryRate()); assertNull(result.delivery().receivingWindowRate());
        assertNull(result.safety().inspectionPassRate()); assertNull(result.loading().matchRate());
        assertNull(result.problems().issueRate()); assertEquals(0, result.delivery().outstandingOrders());
        assertTrue(result.daily().isEmpty());
    }

    @Test void missingDamageFieldAndInvalidCountsCannotBeAssumedPerfect() {
        var one = order(1, 1, day, OrderStatus.COMPLETED); deliver(one, 1, 10, 0, 0, 0, 17).setDamagedBoxCount(null);
        var two = order(2, 1, day, OrderStatus.COMPLETED); deliver(two, 2, 12, 0, 0, 0, 17);
        var result = service.outcomes(range, null, now);
        assertEquals(0, result.delivery().fullOrders()); assertEquals(2, result.delivery().missingQualityOrders());
        assertNull(result.problems().issueRate());
        assertEquals(0, result.problems().shortageOrders()); assertEquals(0, result.problems().damagedOrders());
    }

    @Test void loadsItemDetailsOnlyOnRequestAndUsesTheSameEvidenceAsSummary() {
        var order = order(1, 1, day, OrderStatus.COMPLETED);
        deliver(order, 1, 10, 0, 0, 0, 17);
        order.setBoxCount(99); order.setLoadedAt(day.atTime(8, 0));
        order.setRouteId(1L); route(1, day);
        var item = new OrderItemsEntity(); item.setOrder(order); item.setProductCode("A");
        item.setItemName("飲料"); item.setExpectedQuantity(10); item.setLoadedQuantity(10); item.setUnit("箱");
        when(reads.orderItemsForOrders(anyList())).thenReturn(List.of(item));
        var summary = service.outcomes(range, null, now);
        assertTrue(summary.orders().isEmpty());
        verify(reads, never()).orderItemsForOrders(anyList());
        var result = service.outcomes(range, null, now, true);
        var row = result.orders().getFirst();
        assertEquals(99, row.orderedBoxCount()); assertEquals(10, row.expectedBoxCount());
        assertEquals(20L, row.driverId()); assertTrue(row.full()); assertTrue(row.loadingMatched());
        assertEquals(1, row.items().size()); assertEquals(10, row.items().getFirst().loadedQuantity());
        assertEquals(result.delivery().fullOrders(), result.orders().stream().filter(r -> r.due() && r.full()).count());
        assertEquals(result.loading().matchedOrders(), result.orders().stream().filter(r -> r.loadingMatched()).count());
    }

    @Test void detailRowsExposeUnknownCountsRatherThanFillingFromAnEditedOrder() {
        var order = order(1, 1, day, OrderStatus.COMPLETED);
        deliver(order, 1, 10, 0, 0, 0, 17).setExpectedBoxCount(null);
        order.setBoxCount(999);
        var result = service.outcomes(range, null, now, true);
        assertNull(result.orders().getFirst().expectedBoxCount());
        assertTrue(result.orders().getFirst().missingQuality());
        assertEquals(1, result.delivery().missingQualityOrders());
    }

    @Test void countsExecutedFollowUpOrdersNotApprovedOrdersOrAdditionalTrips() {
        var normal = order(1, 1, day, OrderStatus.COMPLETED); deliver(normal, 1, 10, 0, 0, 0, 17);
        var executed = order(2, 1, day, OrderStatus.NO_SIGNATURE); noSignature(executed, 2);
        executed.setParentOrderId(99L); executed.setOrderType(OrderType.REDELIVERY);
        // Repeated delivery evidence does not inflate the number of executed orders.
        noSignature(executed, 3);
        var waiting = order(3, 2, day, OrderStatus.CONFIRMED);
        waiting.setParentOrderId(100L); waiting.setOrderType(OrderType.REPLENISHMENT);
        var manualReplenishment = order(4, 2, day, OrderStatus.COMPLETED);
        manualReplenishment.setOrderType(OrderType.REPLENISHMENT); deliver(manualReplenishment, 4, 10, 0, 0, 0, 17);
        var unapproved = order(5, 1, day, OrderStatus.PENDING_CONFIRM);
        unapproved.setParentOrderId(101L); unapproved.setOrderType(OrderType.REDELIVERY);
        var cancelled = order(6, 1, day, OrderStatus.CANCELLED);
        cancelled.setParentOrderId(102L); cancelled.setOrderType(OrderType.REDELIVERY);
        var result = service.outcomes(range, null, now, true);
        assertEquals(3, result.recovery().attemptedOrders());
        assertEquals(2, result.recovery().recoveryOrders()); assertEquals(1, result.recovery().attemptedRecoveryOrders());
        assertEquals(0, result.recovery().deliveredRecoveryOrders()); assertEquals(2, result.recovery().outstandingRecoveryOrders());
        assertEquals(100.0 / 3, result.recovery().recoveryShare(), .0001);
        assertEquals(1, result.orders().stream().filter(r -> r.recovery() && r.attempted()).count());
        assertFalse(result.orders().stream().filter(r -> r.orderId() == 4L).findFirst().orElseThrow().recovery());
    }

    @Test void tracesRecordedRecoveryCauseEvenWhenOriginalOrderIsOutsideTheRange() {
        var retry = order(1, 1, day, OrderStatus.COMPLETED);
        retry.setParentOrderId(999L); retry.setOrderType(OrderType.REDELIVERY);
        deliver(retry, 1, 10, 0, 0, 0, 17);
        var cause = new ExceptionCasesEntity(); cause.setId(1L); cause.setOrderId(999L);
        cause.setFollowUpOrderId(1L); cause.setType(ExceptionType.NO_SIGNATURE);
        cause.setCreatedAt(day.minusDays(5).atTime(17, 0)); cases.add(cause);
        var result = service.outcomes(range, null, now, true);
        assertEquals("無人簽收重送", result.orders().getFirst().recoveryReason());
        assertEquals(999L, result.orders().getFirst().parentOrderId());
        assertEquals(1, result.recovery().deliveredRecoveryOrders());
        verify(reads).exceptionsForOrders(argThat(ids -> ids.contains(999L)));
    }

    @Test void missingCauseAndFutureDeliveryDoNotInventExecutionOrReasons() {
        var retry = order(1, 1, day, OrderStatus.CONFIRMED);
        retry.setParentOrderId(99L); retry.setOrderType(OrderType.REPLENISHMENT);
        var record = deliver(retry, 1, 10, 0, 0, 0, 17);
        record.setArrivedAt(day.plusDays(1).atTime(12, 0)); record.setDeliveredAt(day.plusDays(1).atTime(12, 10));
        var result = service.outcomes(range, null, now, true);
        assertNull(result.recovery().recoveryShare()); assertEquals(0, result.recovery().attemptedRecoveryOrders());
        assertFalse(result.orders().getFirst().attempted()); assertNull(result.orders().getFirst().recoveryReason());
    }

    @Test void recoveryFiltersByWarehouseAndKeepsZeroDifferentFromMissingDenominator() {
        var normal = order(1, 1, day, OrderStatus.COMPLETED); deliver(normal, 1, 10, 0, 0, 0, 17);
        var retry = order(2, 2, day, OrderStatus.COMPLETED); deliver(retry, 2, 10, 0, 0, 0, 17);
        retry.setParentOrderId(99L); retry.setOrderType(OrderType.REDELIVERY);
        assertEquals(50, service.outcomes(range, null, now).recovery().recoveryShare());
        assertEquals(0, service.outcomes(range, 1L, now).recovery().recoveryShare());
        assertEquals(100, service.outcomes(range, 2L, now).recovery().recoveryShare());
        assertNull(service.outcomes(range, 3L, now).recovery().recoveryShare());
    }

    private OrdersEntity order(long id, long wh, LocalDate date, OrderStatus status) {
        var order = new OrdersEntity(); order.setId(id); order.setWarehouseId(wh); order.setStoreId(10L);
        order.setDeliveryDate(date); order.setStatus(status); order.setBoxCount(10); orders.add(order); return order;
    }
    private DeliveryRecordsEntity deliver(OrdersEntity order, long id, int boxes, int shortage, int damage, int replacement, int hour) {
        var r = new DeliveryRecordsEntity(); r.setId(id); r.setOrderId(order.getId());
        r.setArrivedAt(order.getDeliveryDate().atTime(hour, 0)); r.setDeliveredAt(order.getDeliveryDate().atTime(hour, 0));
        r.setExpectedBoxCount(10); r.setDeliveredBoxCount(boxes); r.setShortageBoxCount(shortage);
        r.setDamagedBoxCount(damage); r.setReplacementRequiredBoxCount(replacement); deliveries.add(r); return r;
    }
    private void noSignature(OrdersEntity order, long id) {
        var r = new DeliveryRecordsEntity(); r.setId(id); r.setOrderId(order.getId()); r.setNoSignature(true);
        r.setArrivedAt(order.getDeliveryDate().atTime(16, 0)); r.setHandledAt(order.getDeliveryDate().atTime(16, 30)); deliveries.add(r);
    }
    private void loadingMismatch(OrdersEntity order, long id) {
        var e = new ExceptionCasesEntity(); e.setId(id); e.setOrderId(order.getId()); e.setType(ExceptionType.LOADING_MISMATCH);
        e.setCreatedAt(day.atTime(8, 0)); cases.add(e);
    }
    private void route(long id, LocalDate date) {
        var r = new RoutesEntity(); r.setId(id); r.setWarehouseId(1L); r.setDate(date); r.setDriverId(20L);
        r.setVehicleId(30L); r.setVersion(1); r.setStatus(RouteStatus.PUBLISHED); routes.add(r);
    }
    private PreTripInspectionsEntity inspect(long id, long route, boolean passed) {
        var r = new PreTripInspectionsEntity(); r.setId(id); r.setRouteId(route); r.setDriverId(20L); r.setVehicleId(30L);
        r.setRouteVersion(1); r.setWorkDate(routes.stream().filter(t -> t.getId() == route).findFirst().orElseThrow().getDate());
        r.setSubmittedAt(day.atTime(8, 0)); r.setPassed(passed); inspections.add(r); return r;
    }
}
