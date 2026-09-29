package com.example.backend.dto.respones;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** Observed delivery outcomes, not recipient authentication or an agreed SLA. */
public record ReportOutcomesResponse(LocalDate from, LocalDate to, LocalDateTime asOf,
        Delivery delivery, Safety safety, Loading loading, Problems problems,
        List<WarehouseOutcome> warehouses, List<DeliveryDay> daily, Recovery recovery, List<OrderOutcome> orders) {
    public record Delivery(int dueOrders, int deliveredOrders, int fullOrders, int outstandingOrders,
            int windowEligibleOrders, int windowArrivals, int lateArrivals, int earlyArrivals,
            int missingArrivalOrders, int missingWindowOrders, int missingQualityOrders,
            Double fullDeliveryRate, Double receivingWindowRate) {}
    public record Safety(int assignedRoutes, int inspectedRoutes, int passedRoutes,
            int failedRoutes, int missingInspectionRoutes, Double inspectionPassRate) {}
    public record Loading(int checkedOrders, int matchedOrders, int mismatchedOrders,
            int missingLoadingOrders, int dueUnassignedOrders, Double matchRate) {}
    public record Problems(int assessedOrders, int affectedOrders, int shortageOrders,
            int damagedOrders, int noSignatureOrders, Double issueRate) {}
    public record WarehouseOutcome(Long warehouseId, String warehouseName, Delivery delivery,
            Loading loading, Problems problems) {}
    public record DeliveryDay(LocalDate date, int dueOrders, int fullOrders) {}
    public record Recovery(int attemptedOrders, int recoveryOrders, int attemptedRecoveryOrders,
            int deliveredRecoveryOrders, int outstandingRecoveryOrders, Double recoveryShare) {}
    public record OrderOutcome(Long orderId, String orderNumber, LocalDate date, Long warehouseId,
            String warehouseName, Long storeId, String storeName, Long driverId, String status,
            boolean due, boolean delivered, boolean full, boolean withinWindow, boolean late, boolean early,
            boolean missingArrival, boolean missingQuality, boolean assessed, boolean shortage, boolean damaged,
            boolean noSignature, boolean loadingMatched, boolean loadingMismatch, boolean missingLoading,
            boolean dueUnassigned, LocalDateTime windowStart, LocalDateTime windowEnd,
            LocalDateTime arrivedAt, LocalDateTime deliveredAt, LocalDateTime loadedAt,
            Integer orderedBoxCount, Integer expectedBoxCount, Integer deliveredBoxCount, Integer shortageBoxCount, Integer damagedBoxCount,
            Integer replacementRequiredBoxCount, String loadingIssue, String loadingNotes, List<ItemCheck> items,
            String orderType, Long parentOrderId, boolean attempted, boolean recovery, String recoveryReason) {}
    public record ItemCheck(String productCode, String itemName, Integer expectedQuantity,
            Integer loadedQuantity, String unit, String notes, boolean loadingMismatchReported) {}
}
