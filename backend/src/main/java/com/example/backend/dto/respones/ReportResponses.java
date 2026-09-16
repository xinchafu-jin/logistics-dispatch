package com.example.backend.dto.respones;

import com.example.backend.constants.ExceptionStatus;
import com.example.backend.constants.ExceptionType;
import com.example.backend.constants.RouteStatus;
import com.example.backend.constants.ShiftType;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** Read-only supervisor reports. Distances are km, time spans are minutes, and rates are percentages. */
public final class ReportResponses {
    private ReportResponses() {
    }

    public record Summary(
            LocalDate from, LocalDate to, String completionRateDefinition,
            int totalOrders, long totalBoxes, int distinctStores,
            int pendingConfirmationOrders, int confirmedUnassignedOrders, int assignedOrders,
            int inDeliveryOrders, int completedOrders, int failedOrders, int cancelledOrders,
            int publishedRoutes, int dispatchedDrivers, int dispatchedVehicles,
            int unassignedOrders, long unassignedBoxes,
            int completionEligibleOrders, Double completionRatePercent,
            List<DailySummary> dailyTrend
    ) {
    }

    public record DailySummary(
            LocalDate date, int totalOrders, long totalBoxes, int completedOrders,
            int completionEligibleOrders, Double completionRatePercent
    ) {
    }

    public record Attendance(
            LocalDate from, LocalDate to,
            int scheduledWorkShifts, int scheduledWorkDrivers,
            int dayOffShifts, int dayOffDrivers, int leaveShifts, int leaveDrivers,
            int clockInDueShifts, int clockedInDueShifts, Double clockInRatePercent,
            int clockOutDueShifts, int clockedOutDueShifts, Double clockOutRatePercent,
            int completeDueShifts, Double completeRatePercent,
            List<DriverDay> clockedInDrivers, List<DriverDay> missingClockInDrivers,
            List<DriverDay> missingClockOutDrivers, List<AttendanceRow> shifts
    ) {
    }

    public record DriverDay(Long driverId, String driverName, LocalDate workDate) {
    }

    /** clockOutDueAt includes planned overtime; breakExpectedEndAt is planned, not measured. */
    public record AttendanceRow(
            Long shiftId, Long driverId, String driverName, LocalDate workDate, ShiftType shiftType,
            LocalDateTime scheduledStartAt, LocalDateTime scheduledEndAt,
            LocalDateTime clockOutDueAt, Integer plannedOvertimeMinutes,
            LocalDateTime clockInAt, LocalDateTime clockOutAt,
            Long clockInDeltaMinutes, Long clockOutDeltaMinutes,
            Long minutesAfterScheduledEnd, Long clockSpanMinutes,
            Boolean breakUsed, LocalDateTime breakStartedAt, LocalDateTime breakExpectedEndAt,
            boolean clockInDue, boolean clockOutDue, String dataStatus
    ) {
    }

    public record Routes(LocalDate from, LocalDate to, String plannedDistanceBasis, List<RouteRow> routes) {
    }

    public record RouteRow(
            Long routeId, LocalDate date, Long warehouseId, String warehouseName,
            Long vehicleId, String plateNumber, Long driverId, String driverName, RouteStatus status,
            Integer vehicleCapacityBoxes, Double plannedLoadRatePercent,
            int distinctStores, int orders, long boxes, int completedOrders, int failedOrders,
            int noSignatureOrders, Double plannedKm, Double actualKm,
            Double differenceKm, Double differencePercent, String mileageComparisonStatus,
            LocalDateTime tripStartAt, LocalDateTime tripEndAt, Long tripDurationMinutes,
            List<RouteOrder> deliveryOrder
    ) {
    }

    public record RouteOrder(
            Long orderId, String orderNumber, Integer sequence, Long storeId, String storeName,
            int boxes, String status
    ) {
    }

    public record Drivers(LocalDate from, LocalDate to, List<DriverRow> drivers) {
    }

    /** clockSpanMinutes includes breaks; it is not paid working time. */
    public record DriverRow(
            Long driverId, String driverName, int scheduledWorkDays, int clockInDays,
            int missingClockInDays, Long clockSpanMinutes, Long tripDurationMinutes,
            int startedTrips, int publishedRoutes, Double plannedKm, Double actualKm,
            String actualMileageStatus,
            int completeMileageLogs, int incompleteMileageLogs,
            int assignedOrders, int completedOrders, int failedOrders, long boxes,
            int distinctStores, int noSignatureCount, List<Long> noSignatureOrderIds,
            String deliveryAttributionStatus,
            long plannedOvertimeMinutes, long minutesAfterScheduledEnd
    ) {
    }

    public record Vehicles(
            LocalDate from, LocalDate to, double lowLoadThresholdPercent, List<VehicleRow> vehicles
    ) {
    }

    /** Route counts are plans/assignments, not confirmed physical departures. */
    public record VehicleRow(
            Long vehicleId, String plateNumber, Long warehouseId, Integer capacityBoxes,
            int assignedRoutes, int publishedRoutes, List<LocalDate> routeDates,
            int orders, long boxes, int distinctStores, Double averageLoadRatePercent,
            Double publishedPlannedKm, Double actualKm, String actualMileageStatus,
            List<VehicleRouteLoad> routeLoads
    ) {
    }

    public record VehicleRouteLoad(
            Long routeId, LocalDate date, RouteStatus status, long boxes, Double loadRatePercent,
            boolean lowLoad, boolean possiblyOverloaded
    ) {
    }

    public record Warehouses(LocalDate from, LocalDate to, List<WarehouseRow> warehouses) {
    }

    public record WarehouseRow(
            Long warehouseId, String warehouseName, int orders, long boxes, int distinctStores,
            int routes, int publishedRoutes, int unassignedConfirmedOrders,
            long unassignedConfirmedBoxes, int completedOrders, Double averageLoadRatePercent,
            List<WarehouseDaily> dailyTrend
    ) {
    }

    public record WarehouseDaily(
            LocalDate date, int orders, long boxes, int completedOrders, int routes, int publishedRoutes
    ) {
    }

    public record Stores(LocalDate from, LocalDate to, List<StoreRow> stores) {
    }

    public record StoreRow(
            Long storeId, String storeName, int orders, long boxes,
            int completedOrders, int failedOrders, int noSignatureAttempts,
            List<StoreDaily> dailyTrend, List<DeliveryEvent> deliveries
    ) {
    }

    public record StoreDaily(LocalDate date, int orders, long boxes, int completedOrders, int failedOrders) {
    }

    public record DeliveryEvent(
            Long deliveryRecordId, Long orderId, String orderNumber, LocalDate deliveryDate,
            LocalDateTime arrivedAt, LocalDateTime deliveredAt, Boolean noSignature, String photoUrl
    ) {
    }

    public record Exceptions(
            LocalDate from, LocalDate to, int recordedCases, int openCases, int closedCases,
            int noSignatureCases, String coverage,
            List<RepeatedLocation> repeatedStores, List<RepeatedLocation> repeatedRoutes,
            List<ExceptionRow> cases
    ) {
    }

    public record RepeatedLocation(Long id, int count) {
    }

    public record ExceptionRow(
            Long exceptionId, ExceptionType type, ExceptionStatus status,
            LocalDateTime createdAt, LocalDateTime handledAt, Long resolutionMinutes,
            String handledBy, String description, String resolution,
            Long orderId, String orderNumber, Long warehouseId, Long storeId,
            Long driverId, String driverAttributionStatus,
            Long routeId, String photoUrl, String photoMatchStatus
    ) {
    }
}
