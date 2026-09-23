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

    public static class Summary {
        private LocalDate from;
        private LocalDate to;
        private String completionRateDefinition;
        private int totalOrders;
        private long totalBoxes;
        private int distinctStores;
        private int pendingConfirmationOrders;
        private int confirmedUnassignedOrders;
        private int assignedOrders;
        private int inDeliveryOrders;
        private int completedOrders;
        private int failedOrders;
        private int cancelledOrders;
        private int publishedRoutes;
        private int dispatchedDrivers;
        private int dispatchedVehicles;
        private int unassignedOrders;
        private long unassignedBoxes;
        private int completionEligibleOrders;
        private Double completionRatePercent;
        private List<DailySummary> dailyTrend;

        public Summary() {
        }

        public Summary(
                LocalDate from,
                LocalDate to,
                String completionRateDefinition,
                int totalOrders,
                long totalBoxes,
                int distinctStores,
                int pendingConfirmationOrders,
                int confirmedUnassignedOrders,
                int assignedOrders,
                int inDeliveryOrders,
                int completedOrders,
                int failedOrders,
                int cancelledOrders,
                int publishedRoutes,
                int dispatchedDrivers,
                int dispatchedVehicles,
                int unassignedOrders,
                long unassignedBoxes,
                int completionEligibleOrders,
                Double completionRatePercent,
                List<DailySummary> dailyTrend
        ) {
            this.from = from;
            this.to = to;
            this.completionRateDefinition = completionRateDefinition;
            this.totalOrders = totalOrders;
            this.totalBoxes = totalBoxes;
            this.distinctStores = distinctStores;
            this.pendingConfirmationOrders = pendingConfirmationOrders;
            this.confirmedUnassignedOrders = confirmedUnassignedOrders;
            this.assignedOrders = assignedOrders;
            this.inDeliveryOrders = inDeliveryOrders;
            this.completedOrders = completedOrders;
            this.failedOrders = failedOrders;
            this.cancelledOrders = cancelledOrders;
            this.publishedRoutes = publishedRoutes;
            this.dispatchedDrivers = dispatchedDrivers;
            this.dispatchedVehicles = dispatchedVehicles;
            this.unassignedOrders = unassignedOrders;
            this.unassignedBoxes = unassignedBoxes;
            this.completionEligibleOrders = completionEligibleOrders;
            this.completionRatePercent = completionRatePercent;
            this.dailyTrend = dailyTrend;
        }

        public LocalDate getFrom() {
            return from;
        }

        public void setFrom(LocalDate from) {
            this.from = from;
        }

        public LocalDate getTo() {
            return to;
        }

        public void setTo(LocalDate to) {
            this.to = to;
        }

        public String getCompletionRateDefinition() {
            return completionRateDefinition;
        }

        public void setCompletionRateDefinition(String completionRateDefinition) {
            this.completionRateDefinition = completionRateDefinition;
        }

        public int getTotalOrders() {
            return totalOrders;
        }

        public void setTotalOrders(int totalOrders) {
            this.totalOrders = totalOrders;
        }

        public long getTotalBoxes() {
            return totalBoxes;
        }

        public void setTotalBoxes(long totalBoxes) {
            this.totalBoxes = totalBoxes;
        }

        public int getDistinctStores() {
            return distinctStores;
        }

        public void setDistinctStores(int distinctStores) {
            this.distinctStores = distinctStores;
        }

        public int getPendingConfirmationOrders() {
            return pendingConfirmationOrders;
        }

        public void setPendingConfirmationOrders(int pendingConfirmationOrders) {
            this.pendingConfirmationOrders = pendingConfirmationOrders;
        }

        public int getConfirmedUnassignedOrders() {
            return confirmedUnassignedOrders;
        }

        public void setConfirmedUnassignedOrders(int confirmedUnassignedOrders) {
            this.confirmedUnassignedOrders = confirmedUnassignedOrders;
        }

        public int getAssignedOrders() {
            return assignedOrders;
        }

        public void setAssignedOrders(int assignedOrders) {
            this.assignedOrders = assignedOrders;
        }

        public int getInDeliveryOrders() {
            return inDeliveryOrders;
        }

        public void setInDeliveryOrders(int inDeliveryOrders) {
            this.inDeliveryOrders = inDeliveryOrders;
        }

        public int getCompletedOrders() {
            return completedOrders;
        }

        public void setCompletedOrders(int completedOrders) {
            this.completedOrders = completedOrders;
        }

        public int getFailedOrders() {
            return failedOrders;
        }

        public void setFailedOrders(int failedOrders) {
            this.failedOrders = failedOrders;
        }

        public int getCancelledOrders() {
            return cancelledOrders;
        }

        public void setCancelledOrders(int cancelledOrders) {
            this.cancelledOrders = cancelledOrders;
        }

        public int getPublishedRoutes() {
            return publishedRoutes;
        }

        public void setPublishedRoutes(int publishedRoutes) {
            this.publishedRoutes = publishedRoutes;
        }

        public int getDispatchedDrivers() {
            return dispatchedDrivers;
        }

        public void setDispatchedDrivers(int dispatchedDrivers) {
            this.dispatchedDrivers = dispatchedDrivers;
        }

        public int getDispatchedVehicles() {
            return dispatchedVehicles;
        }

        public void setDispatchedVehicles(int dispatchedVehicles) {
            this.dispatchedVehicles = dispatchedVehicles;
        }

        public int getUnassignedOrders() {
            return unassignedOrders;
        }

        public void setUnassignedOrders(int unassignedOrders) {
            this.unassignedOrders = unassignedOrders;
        }

        public long getUnassignedBoxes() {
            return unassignedBoxes;
        }

        public void setUnassignedBoxes(long unassignedBoxes) {
            this.unassignedBoxes = unassignedBoxes;
        }

        public int getCompletionEligibleOrders() {
            return completionEligibleOrders;
        }

        public void setCompletionEligibleOrders(int completionEligibleOrders) {
            this.completionEligibleOrders = completionEligibleOrders;
        }

        public Double getCompletionRatePercent() {
            return completionRatePercent;
        }

        public void setCompletionRatePercent(Double completionRatePercent) {
            this.completionRatePercent = completionRatePercent;
        }

        public List<DailySummary> getDailyTrend() {
            return dailyTrend;
        }

        public void setDailyTrend(List<DailySummary> dailyTrend) {
            this.dailyTrend = dailyTrend;
        }
    }

    public static class DailySummary {
        private LocalDate date;
        private int totalOrders;
        private long totalBoxes;
        private int completedOrders;
        private int completionEligibleOrders;
        private Double completionRatePercent;

        public DailySummary() {
        }

        public DailySummary(
                LocalDate date,
                int totalOrders,
                long totalBoxes,
                int completedOrders,
                int completionEligibleOrders,
                Double completionRatePercent
        ) {
            this.date = date;
            this.totalOrders = totalOrders;
            this.totalBoxes = totalBoxes;
            this.completedOrders = completedOrders;
            this.completionEligibleOrders = completionEligibleOrders;
            this.completionRatePercent = completionRatePercent;
        }

        public LocalDate getDate() {
            return date;
        }

        public void setDate(LocalDate date) {
            this.date = date;
        }

        public int getTotalOrders() {
            return totalOrders;
        }

        public void setTotalOrders(int totalOrders) {
            this.totalOrders = totalOrders;
        }

        public long getTotalBoxes() {
            return totalBoxes;
        }

        public void setTotalBoxes(long totalBoxes) {
            this.totalBoxes = totalBoxes;
        }

        public int getCompletedOrders() {
            return completedOrders;
        }

        public void setCompletedOrders(int completedOrders) {
            this.completedOrders = completedOrders;
        }

        public int getCompletionEligibleOrders() {
            return completionEligibleOrders;
        }

        public void setCompletionEligibleOrders(int completionEligibleOrders) {
            this.completionEligibleOrders = completionEligibleOrders;
        }

        public Double getCompletionRatePercent() {
            return completionRatePercent;
        }

        public void setCompletionRatePercent(Double completionRatePercent) {
            this.completionRatePercent = completionRatePercent;
        }
    }

    public static class Attendance {
        private LocalDate from;
        private LocalDate to;
        private int scheduledWorkShifts;
        private int scheduledWorkDrivers;
        private int dayOffShifts;
        private int dayOffDrivers;
        private int leaveShifts;
        private int leaveDrivers;
        private int clockInDueShifts;
        private int clockedInDueShifts;
        private Double clockInRatePercent;
        private int clockOutDueShifts;
        private int clockedOutDueShifts;
        private Double clockOutRatePercent;
        private int completeDueShifts;
        private Double completeRatePercent;
        private List<DriverDay> clockedInDrivers;
        private List<DriverDay> missingClockInDrivers;
        private List<DriverDay> missingClockOutDrivers;
        private List<AttendanceRow> shifts;

        public Attendance() {
        }

        public Attendance(
                LocalDate from,
                LocalDate to,
                int scheduledWorkShifts,
                int scheduledWorkDrivers,
                int dayOffShifts,
                int dayOffDrivers,
                int leaveShifts,
                int leaveDrivers,
                int clockInDueShifts,
                int clockedInDueShifts,
                Double clockInRatePercent,
                int clockOutDueShifts,
                int clockedOutDueShifts,
                Double clockOutRatePercent,
                int completeDueShifts,
                Double completeRatePercent,
                List<DriverDay> clockedInDrivers,
                List<DriverDay> missingClockInDrivers,
                List<DriverDay> missingClockOutDrivers,
                List<AttendanceRow> shifts
        ) {
            this.from = from;
            this.to = to;
            this.scheduledWorkShifts = scheduledWorkShifts;
            this.scheduledWorkDrivers = scheduledWorkDrivers;
            this.dayOffShifts = dayOffShifts;
            this.dayOffDrivers = dayOffDrivers;
            this.leaveShifts = leaveShifts;
            this.leaveDrivers = leaveDrivers;
            this.clockInDueShifts = clockInDueShifts;
            this.clockedInDueShifts = clockedInDueShifts;
            this.clockInRatePercent = clockInRatePercent;
            this.clockOutDueShifts = clockOutDueShifts;
            this.clockedOutDueShifts = clockedOutDueShifts;
            this.clockOutRatePercent = clockOutRatePercent;
            this.completeDueShifts = completeDueShifts;
            this.completeRatePercent = completeRatePercent;
            this.clockedInDrivers = clockedInDrivers;
            this.missingClockInDrivers = missingClockInDrivers;
            this.missingClockOutDrivers = missingClockOutDrivers;
            this.shifts = shifts;
        }

        public LocalDate getFrom() {
            return from;
        }

        public void setFrom(LocalDate from) {
            this.from = from;
        }

        public LocalDate getTo() {
            return to;
        }

        public void setTo(LocalDate to) {
            this.to = to;
        }

        public int getScheduledWorkShifts() {
            return scheduledWorkShifts;
        }

        public void setScheduledWorkShifts(int scheduledWorkShifts) {
            this.scheduledWorkShifts = scheduledWorkShifts;
        }

        public int getScheduledWorkDrivers() {
            return scheduledWorkDrivers;
        }

        public void setScheduledWorkDrivers(int scheduledWorkDrivers) {
            this.scheduledWorkDrivers = scheduledWorkDrivers;
        }

        public int getDayOffShifts() {
            return dayOffShifts;
        }

        public void setDayOffShifts(int dayOffShifts) {
            this.dayOffShifts = dayOffShifts;
        }

        public int getDayOffDrivers() {
            return dayOffDrivers;
        }

        public void setDayOffDrivers(int dayOffDrivers) {
            this.dayOffDrivers = dayOffDrivers;
        }

        public int getLeaveShifts() {
            return leaveShifts;
        }

        public void setLeaveShifts(int leaveShifts) {
            this.leaveShifts = leaveShifts;
        }

        public int getLeaveDrivers() {
            return leaveDrivers;
        }

        public void setLeaveDrivers(int leaveDrivers) {
            this.leaveDrivers = leaveDrivers;
        }

        public int getClockInDueShifts() {
            return clockInDueShifts;
        }

        public void setClockInDueShifts(int clockInDueShifts) {
            this.clockInDueShifts = clockInDueShifts;
        }

        public int getClockedInDueShifts() {
            return clockedInDueShifts;
        }

        public void setClockedInDueShifts(int clockedInDueShifts) {
            this.clockedInDueShifts = clockedInDueShifts;
        }

        public Double getClockInRatePercent() {
            return clockInRatePercent;
        }

        public void setClockInRatePercent(Double clockInRatePercent) {
            this.clockInRatePercent = clockInRatePercent;
        }

        public int getClockOutDueShifts() {
            return clockOutDueShifts;
        }

        public void setClockOutDueShifts(int clockOutDueShifts) {
            this.clockOutDueShifts = clockOutDueShifts;
        }

        public int getClockedOutDueShifts() {
            return clockedOutDueShifts;
        }

        public void setClockedOutDueShifts(int clockedOutDueShifts) {
            this.clockedOutDueShifts = clockedOutDueShifts;
        }

        public Double getClockOutRatePercent() {
            return clockOutRatePercent;
        }

        public void setClockOutRatePercent(Double clockOutRatePercent) {
            this.clockOutRatePercent = clockOutRatePercent;
        }

        public int getCompleteDueShifts() {
            return completeDueShifts;
        }

        public void setCompleteDueShifts(int completeDueShifts) {
            this.completeDueShifts = completeDueShifts;
        }

        public Double getCompleteRatePercent() {
            return completeRatePercent;
        }

        public void setCompleteRatePercent(Double completeRatePercent) {
            this.completeRatePercent = completeRatePercent;
        }

        public List<DriverDay> getClockedInDrivers() {
            return clockedInDrivers;
        }

        public void setClockedInDrivers(List<DriverDay> clockedInDrivers) {
            this.clockedInDrivers = clockedInDrivers;
        }

        public List<DriverDay> getMissingClockInDrivers() {
            return missingClockInDrivers;
        }

        public void setMissingClockInDrivers(List<DriverDay> missingClockInDrivers) {
            this.missingClockInDrivers = missingClockInDrivers;
        }

        public List<DriverDay> getMissingClockOutDrivers() {
            return missingClockOutDrivers;
        }

        public void setMissingClockOutDrivers(List<DriverDay> missingClockOutDrivers) {
            this.missingClockOutDrivers = missingClockOutDrivers;
        }

        public List<AttendanceRow> getShifts() {
            return shifts;
        }

        public void setShifts(List<AttendanceRow> shifts) {
            this.shifts = shifts;
        }
    }

    public static class DriverDay {
        private Long driverId;
        private String driverName;
        private LocalDate workDate;

        public DriverDay() {
        }

        public DriverDay(
                Long driverId,
                String driverName,
                LocalDate workDate
        ) {
            this.driverId = driverId;
            this.driverName = driverName;
            this.workDate = workDate;
        }

        public Long getDriverId() {
            return driverId;
        }

        public void setDriverId(Long driverId) {
            this.driverId = driverId;
        }

        public String getDriverName() {
            return driverName;
        }

        public void setDriverName(String driverName) {
            this.driverName = driverName;
        }

        public LocalDate getWorkDate() {
            return workDate;
        }

        public void setWorkDate(LocalDate workDate) {
            this.workDate = workDate;
        }
    }

    /** clockOutDueAt includes planned overtime; breakExpectedEndAt is planned, not measured. */
    public static class AttendanceRow {
        private Long shiftId;
        private Long driverId;
        private String driverName;
        private LocalDate workDate;
        private ShiftType shiftType;
        private LocalDateTime scheduledStartAt;
        private LocalDateTime scheduledEndAt;
        private LocalDateTime clockOutDueAt;
        private LocalDateTime clockInAt;
        private LocalDateTime clockOutAt;
        private Long clockInDeltaMinutes;
        private Long clockOutDeltaMinutes;
        private Long minutesAfterScheduledEnd;
        private Long clockSpanMinutes;
        private Boolean breakUsed;
        private LocalDateTime breakStartedAt;
        private LocalDateTime breakExpectedEndAt;
        private boolean clockInDue;
        private boolean clockOutDue;
        private String dataStatus;

        public AttendanceRow() {
        }

        public AttendanceRow(
                Long shiftId,
                Long driverId,
                String driverName,
                LocalDate workDate,
                ShiftType shiftType,
                LocalDateTime scheduledStartAt,
                LocalDateTime scheduledEndAt,
                LocalDateTime clockOutDueAt,
                LocalDateTime clockInAt,
                LocalDateTime clockOutAt,
                Long clockInDeltaMinutes,
                Long clockOutDeltaMinutes,
                Long minutesAfterScheduledEnd,
                Long clockSpanMinutes,
                Boolean breakUsed,
                LocalDateTime breakStartedAt,
                LocalDateTime breakExpectedEndAt,
                boolean clockInDue,
                boolean clockOutDue,
                String dataStatus
        ) {
            this.shiftId = shiftId;
            this.driverId = driverId;
            this.driverName = driverName;
            this.workDate = workDate;
            this.shiftType = shiftType;
            this.scheduledStartAt = scheduledStartAt;
            this.scheduledEndAt = scheduledEndAt;
            this.clockOutDueAt = clockOutDueAt;
            this.clockInAt = clockInAt;
            this.clockOutAt = clockOutAt;
            this.clockInDeltaMinutes = clockInDeltaMinutes;
            this.clockOutDeltaMinutes = clockOutDeltaMinutes;
            this.minutesAfterScheduledEnd = minutesAfterScheduledEnd;
            this.clockSpanMinutes = clockSpanMinutes;
            this.breakUsed = breakUsed;
            this.breakStartedAt = breakStartedAt;
            this.breakExpectedEndAt = breakExpectedEndAt;
            this.clockInDue = clockInDue;
            this.clockOutDue = clockOutDue;
            this.dataStatus = dataStatus;
        }

        public Long getShiftId() {
            return shiftId;
        }

        public void setShiftId(Long shiftId) {
            this.shiftId = shiftId;
        }

        public Long getDriverId() {
            return driverId;
        }

        public void setDriverId(Long driverId) {
            this.driverId = driverId;
        }

        public String getDriverName() {
            return driverName;
        }

        public void setDriverName(String driverName) {
            this.driverName = driverName;
        }

        public LocalDate getWorkDate() {
            return workDate;
        }

        public void setWorkDate(LocalDate workDate) {
            this.workDate = workDate;
        }

        public ShiftType getShiftType() {
            return shiftType;
        }

        public void setShiftType(ShiftType shiftType) {
            this.shiftType = shiftType;
        }

        public LocalDateTime getScheduledStartAt() {
            return scheduledStartAt;
        }

        public void setScheduledStartAt(LocalDateTime scheduledStartAt) {
            this.scheduledStartAt = scheduledStartAt;
        }

        public LocalDateTime getScheduledEndAt() {
            return scheduledEndAt;
        }

        public void setScheduledEndAt(LocalDateTime scheduledEndAt) {
            this.scheduledEndAt = scheduledEndAt;
        }

        public LocalDateTime getClockOutDueAt() {
            return clockOutDueAt;
        }

        public void setClockOutDueAt(LocalDateTime clockOutDueAt) {
            this.clockOutDueAt = clockOutDueAt;
        }

        public LocalDateTime getClockInAt() {
            return clockInAt;
        }

        public void setClockInAt(LocalDateTime clockInAt) {
            this.clockInAt = clockInAt;
        }

        public LocalDateTime getClockOutAt() {
            return clockOutAt;
        }

        public void setClockOutAt(LocalDateTime clockOutAt) {
            this.clockOutAt = clockOutAt;
        }

        public Long getClockInDeltaMinutes() {
            return clockInDeltaMinutes;
        }

        public void setClockInDeltaMinutes(Long clockInDeltaMinutes) {
            this.clockInDeltaMinutes = clockInDeltaMinutes;
        }

        public Long getClockOutDeltaMinutes() {
            return clockOutDeltaMinutes;
        }

        public void setClockOutDeltaMinutes(Long clockOutDeltaMinutes) {
            this.clockOutDeltaMinutes = clockOutDeltaMinutes;
        }

        public Long getMinutesAfterScheduledEnd() {
            return minutesAfterScheduledEnd;
        }

        public void setMinutesAfterScheduledEnd(Long minutesAfterScheduledEnd) {
            this.minutesAfterScheduledEnd = minutesAfterScheduledEnd;
        }

        public Long getClockSpanMinutes() {
            return clockSpanMinutes;
        }

        public void setClockSpanMinutes(Long clockSpanMinutes) {
            this.clockSpanMinutes = clockSpanMinutes;
        }

        public Boolean getBreakUsed() {
            return breakUsed;
        }

        public void setBreakUsed(Boolean breakUsed) {
            this.breakUsed = breakUsed;
        }

        public LocalDateTime getBreakStartedAt() {
            return breakStartedAt;
        }

        public void setBreakStartedAt(LocalDateTime breakStartedAt) {
            this.breakStartedAt = breakStartedAt;
        }

        public LocalDateTime getBreakExpectedEndAt() {
            return breakExpectedEndAt;
        }

        public void setBreakExpectedEndAt(LocalDateTime breakExpectedEndAt) {
            this.breakExpectedEndAt = breakExpectedEndAt;
        }

        public boolean isClockInDue() {
            return clockInDue;
        }

        public void setClockInDue(boolean clockInDue) {
            this.clockInDue = clockInDue;
        }

        public boolean isClockOutDue() {
            return clockOutDue;
        }

        public void setClockOutDue(boolean clockOutDue) {
            this.clockOutDue = clockOutDue;
        }

        public String getDataStatus() {
            return dataStatus;
        }

        public void setDataStatus(String dataStatus) {
            this.dataStatus = dataStatus;
        }
    }

    public static class Routes {
        private LocalDate from;
        private LocalDate to;
        private String plannedDistanceBasis;
        private List<RouteRow> routes;

        public Routes() {
        }

        public Routes(
                LocalDate from,
                LocalDate to,
                String plannedDistanceBasis,
                List<RouteRow> routes
        ) {
            this.from = from;
            this.to = to;
            this.plannedDistanceBasis = plannedDistanceBasis;
            this.routes = routes;
        }

        public LocalDate getFrom() {
            return from;
        }

        public void setFrom(LocalDate from) {
            this.from = from;
        }

        public LocalDate getTo() {
            return to;
        }

        public void setTo(LocalDate to) {
            this.to = to;
        }

        public String getPlannedDistanceBasis() {
            return plannedDistanceBasis;
        }

        public void setPlannedDistanceBasis(String plannedDistanceBasis) {
            this.plannedDistanceBasis = plannedDistanceBasis;
        }

        public List<RouteRow> getRoutes() {
            return routes;
        }

        public void setRoutes(List<RouteRow> routes) {
            this.routes = routes;
        }
    }

    public static class RouteRow {
        private Long routeId;
        private LocalDate date;
        private Long warehouseId;
        private String warehouseName;
        private Long vehicleId;
        private String plateNumber;
        private Long driverId;
        private String driverName;
        private RouteStatus status;
        private Integer vehicleCapacityBoxes;
        private Double plannedLoadRatePercent;
        private int distinctStores;
        private int orders;
        private long boxes;
        private int completedOrders;
        private int failedOrders;
        private int noSignatureOrders;
        private Double plannedKm;
        private Double plannedFuelCost;
        private Integer plannedWorkMinutes;
        private Double systemKm;
        private Double actualKm;
        private Double differenceKm;
        private Double differencePercent;
        private String mileageComparisonStatus;
        private LocalDateTime tripStartAt;
        private LocalDateTime tripEndAt;
        private Long tripDurationMinutes;
        private List<RouteOrder> deliveryOrder;
        private List<RouteLegRow> routeLegs;

        public RouteRow() {
        }

        public RouteRow(
                Long routeId,
                LocalDate date,
                Long warehouseId,
                String warehouseName,
                Long vehicleId,
                String plateNumber,
                Long driverId,
                String driverName,
                RouteStatus status,
                Integer vehicleCapacityBoxes,
                Double plannedLoadRatePercent,
                int distinctStores,
                int orders,
                long boxes,
                int completedOrders,
                int failedOrders,
                int noSignatureOrders,
                Double plannedKm,
                Double plannedFuelCost,
                Integer plannedWorkMinutes,
                Double actualKm,
                Double differenceKm,
                Double differencePercent,
                String mileageComparisonStatus,
                LocalDateTime tripStartAt,
                LocalDateTime tripEndAt,
                Long tripDurationMinutes,
                List<RouteOrder> deliveryOrder
        ) {
            this.routeId = routeId;
            this.date = date;
            this.warehouseId = warehouseId;
            this.warehouseName = warehouseName;
            this.vehicleId = vehicleId;
            this.plateNumber = plateNumber;
            this.driverId = driverId;
            this.driverName = driverName;
            this.status = status;
            this.vehicleCapacityBoxes = vehicleCapacityBoxes;
            this.plannedLoadRatePercent = plannedLoadRatePercent;
            this.distinctStores = distinctStores;
            this.orders = orders;
            this.boxes = boxes;
            this.completedOrders = completedOrders;
            this.failedOrders = failedOrders;
            this.noSignatureOrders = noSignatureOrders;
            this.plannedKm = plannedKm;
            this.plannedFuelCost = plannedFuelCost;
            this.plannedWorkMinutes = plannedWorkMinutes;
            this.actualKm = actualKm;
            this.differenceKm = differenceKm;
            this.differencePercent = differencePercent;
            this.mileageComparisonStatus = mileageComparisonStatus;
            this.tripStartAt = tripStartAt;
            this.tripEndAt = tripEndAt;
            this.tripDurationMinutes = tripDurationMinutes;
            this.deliveryOrder = deliveryOrder;
        }

        public Long getRouteId() {
            return routeId;
        }

        public void setRouteId(Long routeId) {
            this.routeId = routeId;
        }

        public LocalDate getDate() {
            return date;
        }

        public void setDate(LocalDate date) {
            this.date = date;
        }

        public Long getWarehouseId() {
            return warehouseId;
        }

        public void setWarehouseId(Long warehouseId) {
            this.warehouseId = warehouseId;
        }

        public String getWarehouseName() {
            return warehouseName;
        }

        public void setWarehouseName(String warehouseName) {
            this.warehouseName = warehouseName;
        }

        public Long getVehicleId() {
            return vehicleId;
        }

        public void setVehicleId(Long vehicleId) {
            this.vehicleId = vehicleId;
        }

        public String getPlateNumber() {
            return plateNumber;
        }

        public void setPlateNumber(String plateNumber) {
            this.plateNumber = plateNumber;
        }

        public Long getDriverId() {
            return driverId;
        }

        public void setDriverId(Long driverId) {
            this.driverId = driverId;
        }

        public String getDriverName() {
            return driverName;
        }

        public void setDriverName(String driverName) {
            this.driverName = driverName;
        }

        public RouteStatus getStatus() {
            return status;
        }

        public void setStatus(RouteStatus status) {
            this.status = status;
        }

        public Integer getVehicleCapacityBoxes() {
            return vehicleCapacityBoxes;
        }

        public void setVehicleCapacityBoxes(Integer vehicleCapacityBoxes) {
            this.vehicleCapacityBoxes = vehicleCapacityBoxes;
        }

        public Double getPlannedLoadRatePercent() {
            return plannedLoadRatePercent;
        }

        public void setPlannedLoadRatePercent(Double plannedLoadRatePercent) {
            this.plannedLoadRatePercent = plannedLoadRatePercent;
        }

        public int getDistinctStores() {
            return distinctStores;
        }

        public void setDistinctStores(int distinctStores) {
            this.distinctStores = distinctStores;
        }

        public int getOrders() {
            return orders;
        }

        public void setOrders(int orders) {
            this.orders = orders;
        }

        public long getBoxes() {
            return boxes;
        }

        public void setBoxes(long boxes) {
            this.boxes = boxes;
        }

        public int getCompletedOrders() {
            return completedOrders;
        }

        public void setCompletedOrders(int completedOrders) {
            this.completedOrders = completedOrders;
        }

        public int getFailedOrders() {
            return failedOrders;
        }

        public void setFailedOrders(int failedOrders) {
            this.failedOrders = failedOrders;
        }

        public int getNoSignatureOrders() {
            return noSignatureOrders;
        }

        public void setNoSignatureOrders(int noSignatureOrders) {
            this.noSignatureOrders = noSignatureOrders;
        }

        public Double getPlannedKm() {
            return plannedKm;
        }

        public void setPlannedKm(Double plannedKm) {
            this.plannedKm = plannedKm;
        }

        public Double getPlannedFuelCost() {
            return plannedFuelCost;
        }

        public void setPlannedFuelCost(Double plannedFuelCost) {
            this.plannedFuelCost = plannedFuelCost;
        }

        public Integer getPlannedWorkMinutes() {
            return plannedWorkMinutes;
        }

        public void setPlannedWorkMinutes(Integer plannedWorkMinutes) {
            this.plannedWorkMinutes = plannedWorkMinutes;
        }

        public Double getSystemKm() {
            return systemKm;
        }

        public void setSystemKm(Double systemKm) {
            this.systemKm = systemKm;
        }

        public Double getActualKm() {
            return actualKm;
        }

        public void setActualKm(Double actualKm) {
            this.actualKm = actualKm;
        }

        public Double getDifferenceKm() {
            return differenceKm;
        }

        public void setDifferenceKm(Double differenceKm) {
            this.differenceKm = differenceKm;
        }

        public Double getDifferencePercent() {
            return differencePercent;
        }

        public void setDifferencePercent(Double differencePercent) {
            this.differencePercent = differencePercent;
        }

        public String getMileageComparisonStatus() {
            return mileageComparisonStatus;
        }

        public void setMileageComparisonStatus(String mileageComparisonStatus) {
            this.mileageComparisonStatus = mileageComparisonStatus;
        }

        public LocalDateTime getTripStartAt() {
            return tripStartAt;
        }

        public void setTripStartAt(LocalDateTime tripStartAt) {
            this.tripStartAt = tripStartAt;
        }

        public LocalDateTime getTripEndAt() {
            return tripEndAt;
        }

        public void setTripEndAt(LocalDateTime tripEndAt) {
            this.tripEndAt = tripEndAt;
        }

        public Long getTripDurationMinutes() {
            return tripDurationMinutes;
        }

        public void setTripDurationMinutes(Long tripDurationMinutes) {
            this.tripDurationMinutes = tripDurationMinutes;
        }

        public List<RouteOrder> getDeliveryOrder() {
            return deliveryOrder;
        }

        public void setDeliveryOrder(List<RouteOrder> deliveryOrder) {
            this.deliveryOrder = deliveryOrder;
        }

        public List<RouteLegRow> getRouteLegs() {
            return routeLegs;
        }

        public void setRouteLegs(List<RouteLegRow> routeLegs) {
            this.routeLegs = routeLegs;
        }
    }

    /** 主管路線報表中的單一實際行駛區段。 */
    public static class RouteLegRow {
        private Long id;
        private Long mileageLogId;
        private Long driverId;
        private Long vehicleId;
        private Integer sequence;
        private String fromType;
        private Long fromStoreId;
        private String fromName;
        private String toType;
        private Long toStoreId;
        private String toName;
        private Long orderId;
        private Long deliveryRecordId;
        private LocalDateTime startedAt;
        private LocalDateTime endedAt;
        private Long durationMinutes;
        private Double systemKm;
        private Integer gpsPointCount;
        private Integer acceptedSegmentCount;
        private String calculationStatus;

        public RouteLegRow() {
        }

        public RouteLegRow(
                Long id,
                Long mileageLogId,
                Long driverId,
                Long vehicleId,
                Integer sequence,
                String fromType,
                Long fromStoreId,
                String fromName,
                String toType,
                Long toStoreId,
                String toName,
                Long orderId,
                Long deliveryRecordId,
                LocalDateTime startedAt,
                LocalDateTime endedAt,
                Long durationMinutes,
                Double systemKm,
                Integer gpsPointCount,
                Integer acceptedSegmentCount,
                String calculationStatus
        ) {
            this.id = id;
            this.mileageLogId = mileageLogId;
            this.driverId = driverId;
            this.vehicleId = vehicleId;
            this.sequence = sequence;
            this.fromType = fromType;
            this.fromStoreId = fromStoreId;
            this.fromName = fromName;
            this.toType = toType;
            this.toStoreId = toStoreId;
            this.toName = toName;
            this.orderId = orderId;
            this.deliveryRecordId = deliveryRecordId;
            this.startedAt = startedAt;
            this.endedAt = endedAt;
            this.durationMinutes = durationMinutes;
            this.systemKm = systemKm;
            this.gpsPointCount = gpsPointCount;
            this.acceptedSegmentCount = acceptedSegmentCount;
            this.calculationStatus = calculationStatus;
        }

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public Long getMileageLogId() { return mileageLogId; }
        public void setMileageLogId(Long mileageLogId) { this.mileageLogId = mileageLogId; }
        public Long getDriverId() { return driverId; }
        public void setDriverId(Long driverId) { this.driverId = driverId; }
        public Long getVehicleId() { return vehicleId; }
        public void setVehicleId(Long vehicleId) { this.vehicleId = vehicleId; }
        public Integer getSequence() { return sequence; }
        public void setSequence(Integer sequence) { this.sequence = sequence; }
        public String getFromType() { return fromType; }
        public void setFromType(String fromType) { this.fromType = fromType; }
        public Long getFromStoreId() { return fromStoreId; }
        public void setFromStoreId(Long fromStoreId) { this.fromStoreId = fromStoreId; }
        public String getFromName() { return fromName; }
        public void setFromName(String fromName) { this.fromName = fromName; }
        public String getToType() { return toType; }
        public void setToType(String toType) { this.toType = toType; }
        public Long getToStoreId() { return toStoreId; }
        public void setToStoreId(Long toStoreId) { this.toStoreId = toStoreId; }
        public String getToName() { return toName; }
        public void setToName(String toName) { this.toName = toName; }
        public Long getOrderId() { return orderId; }
        public void setOrderId(Long orderId) { this.orderId = orderId; }
        public Long getDeliveryRecordId() { return deliveryRecordId; }
        public void setDeliveryRecordId(Long deliveryRecordId) {
            this.deliveryRecordId = deliveryRecordId;
        }
        public LocalDateTime getStartedAt() { return startedAt; }
        public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
        public LocalDateTime getEndedAt() { return endedAt; }
        public void setEndedAt(LocalDateTime endedAt) { this.endedAt = endedAt; }
        public Long getDurationMinutes() { return durationMinutes; }
        public void setDurationMinutes(Long durationMinutes) { this.durationMinutes = durationMinutes; }
        public Double getSystemKm() { return systemKm; }
        public void setSystemKm(Double systemKm) { this.systemKm = systemKm; }
        public Integer getGpsPointCount() { return gpsPointCount; }
        public void setGpsPointCount(Integer gpsPointCount) { this.gpsPointCount = gpsPointCount; }
        public Integer getAcceptedSegmentCount() { return acceptedSegmentCount; }
        public void setAcceptedSegmentCount(Integer acceptedSegmentCount) {
            this.acceptedSegmentCount = acceptedSegmentCount;
        }
        public String getCalculationStatus() { return calculationStatus; }
        public void setCalculationStatus(String calculationStatus) {
            this.calculationStatus = calculationStatus;
        }
    }

    public static class RouteOrder {
        private Long orderId;
        private String orderNumber;
        private Integer sequence;
        private Long storeId;
        private String storeName;
        private int boxes;
        private String status;

        public RouteOrder() {
        }

        public RouteOrder(
                Long orderId,
                String orderNumber,
                Integer sequence,
                Long storeId,
                String storeName,
                int boxes,
                String status
        ) {
            this.orderId = orderId;
            this.orderNumber = orderNumber;
            this.sequence = sequence;
            this.storeId = storeId;
            this.storeName = storeName;
            this.boxes = boxes;
            this.status = status;
        }

        public Long getOrderId() {
            return orderId;
        }

        public void setOrderId(Long orderId) {
            this.orderId = orderId;
        }

        public String getOrderNumber() {
            return orderNumber;
        }

        public void setOrderNumber(String orderNumber) {
            this.orderNumber = orderNumber;
        }

        public Integer getSequence() {
            return sequence;
        }

        public void setSequence(Integer sequence) {
            this.sequence = sequence;
        }

        public Long getStoreId() {
            return storeId;
        }

        public void setStoreId(Long storeId) {
            this.storeId = storeId;
        }

        public String getStoreName() {
            return storeName;
        }

        public void setStoreName(String storeName) {
            this.storeName = storeName;
        }

        public int getBoxes() {
            return boxes;
        }

        public void setBoxes(int boxes) {
            this.boxes = boxes;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }
    }

    public static class Drivers {
        private LocalDate from;
        private LocalDate to;
        private List<DriverRow> drivers;

        public Drivers() {
        }

        public Drivers(
                LocalDate from,
                LocalDate to,
                List<DriverRow> drivers
        ) {
            this.from = from;
            this.to = to;
            this.drivers = drivers;
        }

        public LocalDate getFrom() {
            return from;
        }

        public void setFrom(LocalDate from) {
            this.from = from;
        }

        public LocalDate getTo() {
            return to;
        }

        public void setTo(LocalDate to) {
            this.to = to;
        }

        public List<DriverRow> getDrivers() {
            return drivers;
        }

        public void setDrivers(List<DriverRow> drivers) {
            this.drivers = drivers;
        }
    }

    /** clockSpanMinutes includes breaks; it is not paid working time. */
    public static class DriverRow {
        private Long driverId;
        private String driverName;
        private int scheduledWorkDays;
        private int clockInDays;
        private int missingClockInDays;
        private Long clockSpanMinutes;
        private Long tripDurationMinutes;
        private int startedTrips;
        private int publishedRoutes;
        private Double plannedKm;
        private Double systemKm;
        private Double actualKm;
        private String actualMileageStatus;
        private int completeMileageLogs;
        private int incompleteMileageLogs;
        private int assignedOrders;
        private int completedOrders;
        private int failedOrders;
        private long boxes;
        private int distinctStores;
        private int noSignatureCount;
        private List<Long> noSignatureOrderIds;
        private String deliveryAttributionStatus;
        private long minutesAfterScheduledEnd;

        public DriverRow() {
        }

        public DriverRow(
                Long driverId,
                String driverName,
                int scheduledWorkDays,
                int clockInDays,
                int missingClockInDays,
                Long clockSpanMinutes,
                Long tripDurationMinutes,
                int startedTrips,
                int publishedRoutes,
                Double plannedKm,
                Double actualKm,
                String actualMileageStatus,
                int completeMileageLogs,
                int incompleteMileageLogs,
                int assignedOrders,
                int completedOrders,
                int failedOrders,
                long boxes,
                int distinctStores,
                int noSignatureCount,
                List<Long> noSignatureOrderIds,
                String deliveryAttributionStatus,
                long minutesAfterScheduledEnd
        ) {
            this.driverId = driverId;
            this.driverName = driverName;
            this.scheduledWorkDays = scheduledWorkDays;
            this.clockInDays = clockInDays;
            this.missingClockInDays = missingClockInDays;
            this.clockSpanMinutes = clockSpanMinutes;
            this.tripDurationMinutes = tripDurationMinutes;
            this.startedTrips = startedTrips;
            this.publishedRoutes = publishedRoutes;
            this.plannedKm = plannedKm;
            this.actualKm = actualKm;
            this.actualMileageStatus = actualMileageStatus;
            this.completeMileageLogs = completeMileageLogs;
            this.incompleteMileageLogs = incompleteMileageLogs;
            this.assignedOrders = assignedOrders;
            this.completedOrders = completedOrders;
            this.failedOrders = failedOrders;
            this.boxes = boxes;
            this.distinctStores = distinctStores;
            this.noSignatureCount = noSignatureCount;
            this.noSignatureOrderIds = noSignatureOrderIds;
            this.deliveryAttributionStatus = deliveryAttributionStatus;
            this.minutesAfterScheduledEnd = minutesAfterScheduledEnd;
        }

        public Long getDriverId() {
            return driverId;
        }

        public void setDriverId(Long driverId) {
            this.driverId = driverId;
        }

        public String getDriverName() {
            return driverName;
        }

        public void setDriverName(String driverName) {
            this.driverName = driverName;
        }

        public int getScheduledWorkDays() {
            return scheduledWorkDays;
        }

        public void setScheduledWorkDays(int scheduledWorkDays) {
            this.scheduledWorkDays = scheduledWorkDays;
        }

        public int getClockInDays() {
            return clockInDays;
        }

        public void setClockInDays(int clockInDays) {
            this.clockInDays = clockInDays;
        }

        public int getMissingClockInDays() {
            return missingClockInDays;
        }

        public void setMissingClockInDays(int missingClockInDays) {
            this.missingClockInDays = missingClockInDays;
        }

        public Long getClockSpanMinutes() {
            return clockSpanMinutes;
        }

        public void setClockSpanMinutes(Long clockSpanMinutes) {
            this.clockSpanMinutes = clockSpanMinutes;
        }

        public Long getTripDurationMinutes() {
            return tripDurationMinutes;
        }

        public void setTripDurationMinutes(Long tripDurationMinutes) {
            this.tripDurationMinutes = tripDurationMinutes;
        }

        public int getStartedTrips() {
            return startedTrips;
        }

        public void setStartedTrips(int startedTrips) {
            this.startedTrips = startedTrips;
        }

        public int getPublishedRoutes() {
            return publishedRoutes;
        }

        public void setPublishedRoutes(int publishedRoutes) {
            this.publishedRoutes = publishedRoutes;
        }

        public Double getPlannedKm() {
            return plannedKm;
        }

        public void setPlannedKm(Double plannedKm) {
            this.plannedKm = plannedKm;
        }

        public Double getSystemKm() {
            return systemKm;
        }

        public void setSystemKm(Double systemKm) {
            this.systemKm = systemKm;
        }

        public Double getActualKm() {
            return actualKm;
        }

        public void setActualKm(Double actualKm) {
            this.actualKm = actualKm;
        }

        public String getActualMileageStatus() {
            return actualMileageStatus;
        }

        public void setActualMileageStatus(String actualMileageStatus) {
            this.actualMileageStatus = actualMileageStatus;
        }

        public int getCompleteMileageLogs() {
            return completeMileageLogs;
        }

        public void setCompleteMileageLogs(int completeMileageLogs) {
            this.completeMileageLogs = completeMileageLogs;
        }

        public int getIncompleteMileageLogs() {
            return incompleteMileageLogs;
        }

        public void setIncompleteMileageLogs(int incompleteMileageLogs) {
            this.incompleteMileageLogs = incompleteMileageLogs;
        }

        public int getAssignedOrders() {
            return assignedOrders;
        }

        public void setAssignedOrders(int assignedOrders) {
            this.assignedOrders = assignedOrders;
        }

        public int getCompletedOrders() {
            return completedOrders;
        }

        public void setCompletedOrders(int completedOrders) {
            this.completedOrders = completedOrders;
        }

        public int getFailedOrders() {
            return failedOrders;
        }

        public void setFailedOrders(int failedOrders) {
            this.failedOrders = failedOrders;
        }

        public long getBoxes() {
            return boxes;
        }

        public void setBoxes(long boxes) {
            this.boxes = boxes;
        }

        public int getDistinctStores() {
            return distinctStores;
        }

        public void setDistinctStores(int distinctStores) {
            this.distinctStores = distinctStores;
        }

        public int getNoSignatureCount() {
            return noSignatureCount;
        }

        public void setNoSignatureCount(int noSignatureCount) {
            this.noSignatureCount = noSignatureCount;
        }

        public List<Long> getNoSignatureOrderIds() {
            return noSignatureOrderIds;
        }

        public void setNoSignatureOrderIds(List<Long> noSignatureOrderIds) {
            this.noSignatureOrderIds = noSignatureOrderIds;
        }

        public String getDeliveryAttributionStatus() {
            return deliveryAttributionStatus;
        }

        public void setDeliveryAttributionStatus(String deliveryAttributionStatus) {
            this.deliveryAttributionStatus = deliveryAttributionStatus;
        }

        public long getMinutesAfterScheduledEnd() {
            return minutesAfterScheduledEnd;
        }

        public void setMinutesAfterScheduledEnd(long minutesAfterScheduledEnd) {
            this.minutesAfterScheduledEnd = minutesAfterScheduledEnd;
        }
    }

    public static class Vehicles {
        private LocalDate from;
        private LocalDate to;
        private double lowLoadThresholdPercent;
        private List<VehicleRow> vehicles;

        public Vehicles() {
        }

        public Vehicles(
                LocalDate from,
                LocalDate to,
                double lowLoadThresholdPercent,
                List<VehicleRow> vehicles
        ) {
            this.from = from;
            this.to = to;
            this.lowLoadThresholdPercent = lowLoadThresholdPercent;
            this.vehicles = vehicles;
        }

        public LocalDate getFrom() {
            return from;
        }

        public void setFrom(LocalDate from) {
            this.from = from;
        }

        public LocalDate getTo() {
            return to;
        }

        public void setTo(LocalDate to) {
            this.to = to;
        }

        public double getLowLoadThresholdPercent() {
            return lowLoadThresholdPercent;
        }

        public void setLowLoadThresholdPercent(double lowLoadThresholdPercent) {
            this.lowLoadThresholdPercent = lowLoadThresholdPercent;
        }

        public List<VehicleRow> getVehicles() {
            return vehicles;
        }

        public void setVehicles(List<VehicleRow> vehicles) {
            this.vehicles = vehicles;
        }
    }

    /** Route counts are plans/assignments, not confirmed physical departures. */
    public static class VehicleRow {
        private Long vehicleId;
        private String plateNumber;
        private Long warehouseId;
        private Integer capacityBoxes;
        private int assignedRoutes;
        private int publishedRoutes;
        private List<LocalDate> routeDates;
        private int orders;
        private long boxes;
        private int distinctStores;
        private Double averageLoadRatePercent;
        private Double publishedPlannedKm;
        private Double systemKm;
        private Double actualKm;
        private String actualMileageStatus;
        private Integer currentOdometerKm;
        private Double cumulativeMileageKm;
        private List<VehicleRouteLoad> routeLoads;

        public VehicleRow() {
        }

        public VehicleRow(
                Long vehicleId,
                String plateNumber,
                Long warehouseId,
                Integer capacityBoxes,
                int assignedRoutes,
                int publishedRoutes,
                List<LocalDate> routeDates,
                int orders,
                long boxes,
                int distinctStores,
                Double averageLoadRatePercent,
                Double publishedPlannedKm,
                Double actualKm,
                String actualMileageStatus,
                Double cumulativeMileageKm,
                List<VehicleRouteLoad> routeLoads
        ) {
            this.vehicleId = vehicleId;
            this.plateNumber = plateNumber;
            this.warehouseId = warehouseId;
            this.capacityBoxes = capacityBoxes;
            this.assignedRoutes = assignedRoutes;
            this.publishedRoutes = publishedRoutes;
            this.routeDates = routeDates;
            this.orders = orders;
            this.boxes = boxes;
            this.distinctStores = distinctStores;
            this.averageLoadRatePercent = averageLoadRatePercent;
            this.publishedPlannedKm = publishedPlannedKm;
            this.actualKm = actualKm;
            this.actualMileageStatus = actualMileageStatus;
            this.cumulativeMileageKm = cumulativeMileageKm;
            this.routeLoads = routeLoads;
        }

        public Long getVehicleId() {
            return vehicleId;
        }

        public void setVehicleId(Long vehicleId) {
            this.vehicleId = vehicleId;
        }

        public String getPlateNumber() {
            return plateNumber;
        }

        public void setPlateNumber(String plateNumber) {
            this.plateNumber = plateNumber;
        }

        public Long getWarehouseId() {
            return warehouseId;
        }

        public void setWarehouseId(Long warehouseId) {
            this.warehouseId = warehouseId;
        }

        public Integer getCapacityBoxes() {
            return capacityBoxes;
        }

        public void setCapacityBoxes(Integer capacityBoxes) {
            this.capacityBoxes = capacityBoxes;
        }

        public int getAssignedRoutes() {
            return assignedRoutes;
        }

        public void setAssignedRoutes(int assignedRoutes) {
            this.assignedRoutes = assignedRoutes;
        }

        public int getPublishedRoutes() {
            return publishedRoutes;
        }

        public void setPublishedRoutes(int publishedRoutes) {
            this.publishedRoutes = publishedRoutes;
        }

        public List<LocalDate> getRouteDates() {
            return routeDates;
        }

        public void setRouteDates(List<LocalDate> routeDates) {
            this.routeDates = routeDates;
        }

        public int getOrders() {
            return orders;
        }

        public void setOrders(int orders) {
            this.orders = orders;
        }

        public long getBoxes() {
            return boxes;
        }

        public void setBoxes(long boxes) {
            this.boxes = boxes;
        }

        public int getDistinctStores() {
            return distinctStores;
        }

        public void setDistinctStores(int distinctStores) {
            this.distinctStores = distinctStores;
        }

        public Double getAverageLoadRatePercent() {
            return averageLoadRatePercent;
        }

        public void setAverageLoadRatePercent(Double averageLoadRatePercent) {
            this.averageLoadRatePercent = averageLoadRatePercent;
        }

        public Double getPublishedPlannedKm() {
            return publishedPlannedKm;
        }

        public void setPublishedPlannedKm(Double publishedPlannedKm) {
            this.publishedPlannedKm = publishedPlannedKm;
        }

        public Double getSystemKm() {
            return systemKm;
        }

        public void setSystemKm(Double systemKm) {
            this.systemKm = systemKm;
        }

        public Double getActualKm() {
            return actualKm;
        }

        public void setActualKm(Double actualKm) {
            this.actualKm = actualKm;
        }

        public String getActualMileageStatus() {
            return actualMileageStatus;
        }

        public void setActualMileageStatus(String actualMileageStatus) {
            this.actualMileageStatus = actualMileageStatus;
        }

        public Integer getCurrentOdometerKm() {
            return currentOdometerKm;
        }

        public void setCurrentOdometerKm(Integer currentOdometerKm) {
            this.currentOdometerKm = currentOdometerKm;
        }

        public Double getCumulativeMileageKm() {
            return cumulativeMileageKm;
        }

        public void setCumulativeMileageKm(Double cumulativeMileageKm) {
            this.cumulativeMileageKm = cumulativeMileageKm;
        }

        public List<VehicleRouteLoad> getRouteLoads() {
            return routeLoads;
        }

        public void setRouteLoads(List<VehicleRouteLoad> routeLoads) {
            this.routeLoads = routeLoads;
        }
    }

    public static class VehicleRouteLoad {
        private Long routeId;
        private LocalDate date;
        private RouteStatus status;
        private long boxes;
        private Double loadRatePercent;
        private boolean lowLoad;
        private boolean possiblyOverloaded;

        public VehicleRouteLoad() {
        }

        public VehicleRouteLoad(
                Long routeId,
                LocalDate date,
                RouteStatus status,
                long boxes,
                Double loadRatePercent,
                boolean lowLoad,
                boolean possiblyOverloaded
        ) {
            this.routeId = routeId;
            this.date = date;
            this.status = status;
            this.boxes = boxes;
            this.loadRatePercent = loadRatePercent;
            this.lowLoad = lowLoad;
            this.possiblyOverloaded = possiblyOverloaded;
        }

        public Long getRouteId() {
            return routeId;
        }

        public void setRouteId(Long routeId) {
            this.routeId = routeId;
        }

        public LocalDate getDate() {
            return date;
        }

        public void setDate(LocalDate date) {
            this.date = date;
        }

        public RouteStatus getStatus() {
            return status;
        }

        public void setStatus(RouteStatus status) {
            this.status = status;
        }

        public long getBoxes() {
            return boxes;
        }

        public void setBoxes(long boxes) {
            this.boxes = boxes;
        }

        public Double getLoadRatePercent() {
            return loadRatePercent;
        }

        public void setLoadRatePercent(Double loadRatePercent) {
            this.loadRatePercent = loadRatePercent;
        }

        public boolean isLowLoad() {
            return lowLoad;
        }

        public void setLowLoad(boolean lowLoad) {
            this.lowLoad = lowLoad;
        }

        public boolean isPossiblyOverloaded() {
            return possiblyOverloaded;
        }

        public void setPossiblyOverloaded(boolean possiblyOverloaded) {
            this.possiblyOverloaded = possiblyOverloaded;
        }
    }

    public static class Warehouses {
        private LocalDate from;
        private LocalDate to;
        private List<WarehouseRow> warehouses;

        public Warehouses() {
        }

        public Warehouses(
                LocalDate from,
                LocalDate to,
                List<WarehouseRow> warehouses
        ) {
            this.from = from;
            this.to = to;
            this.warehouses = warehouses;
        }

        public LocalDate getFrom() {
            return from;
        }

        public void setFrom(LocalDate from) {
            this.from = from;
        }

        public LocalDate getTo() {
            return to;
        }

        public void setTo(LocalDate to) {
            this.to = to;
        }

        public List<WarehouseRow> getWarehouses() {
            return warehouses;
        }

        public void setWarehouses(List<WarehouseRow> warehouses) {
            this.warehouses = warehouses;
        }
    }

    public static class WarehouseRow {
        private Long warehouseId;
        private String warehouseName;
        private int orders;
        private long boxes;
        private int distinctStores;
        private int routes;
        private int publishedRoutes;
        private int unassignedConfirmedOrders;
        private long unassignedConfirmedBoxes;
        private int completedOrders;
        private Double averageLoadRatePercent;
        private List<WarehouseDaily> dailyTrend;

        public WarehouseRow() {
        }

        public WarehouseRow(
                Long warehouseId,
                String warehouseName,
                int orders,
                long boxes,
                int distinctStores,
                int routes,
                int publishedRoutes,
                int unassignedConfirmedOrders,
                long unassignedConfirmedBoxes,
                int completedOrders,
                Double averageLoadRatePercent,
                List<WarehouseDaily> dailyTrend
        ) {
            this.warehouseId = warehouseId;
            this.warehouseName = warehouseName;
            this.orders = orders;
            this.boxes = boxes;
            this.distinctStores = distinctStores;
            this.routes = routes;
            this.publishedRoutes = publishedRoutes;
            this.unassignedConfirmedOrders = unassignedConfirmedOrders;
            this.unassignedConfirmedBoxes = unassignedConfirmedBoxes;
            this.completedOrders = completedOrders;
            this.averageLoadRatePercent = averageLoadRatePercent;
            this.dailyTrend = dailyTrend;
        }

        public Long getWarehouseId() {
            return warehouseId;
        }

        public void setWarehouseId(Long warehouseId) {
            this.warehouseId = warehouseId;
        }

        public String getWarehouseName() {
            return warehouseName;
        }

        public void setWarehouseName(String warehouseName) {
            this.warehouseName = warehouseName;
        }

        public int getOrders() {
            return orders;
        }

        public void setOrders(int orders) {
            this.orders = orders;
        }

        public long getBoxes() {
            return boxes;
        }

        public void setBoxes(long boxes) {
            this.boxes = boxes;
        }

        public int getDistinctStores() {
            return distinctStores;
        }

        public void setDistinctStores(int distinctStores) {
            this.distinctStores = distinctStores;
        }

        public int getRoutes() {
            return routes;
        }

        public void setRoutes(int routes) {
            this.routes = routes;
        }

        public int getPublishedRoutes() {
            return publishedRoutes;
        }

        public void setPublishedRoutes(int publishedRoutes) {
            this.publishedRoutes = publishedRoutes;
        }

        public int getUnassignedConfirmedOrders() {
            return unassignedConfirmedOrders;
        }

        public void setUnassignedConfirmedOrders(int unassignedConfirmedOrders) {
            this.unassignedConfirmedOrders = unassignedConfirmedOrders;
        }

        public long getUnassignedConfirmedBoxes() {
            return unassignedConfirmedBoxes;
        }

        public void setUnassignedConfirmedBoxes(long unassignedConfirmedBoxes) {
            this.unassignedConfirmedBoxes = unassignedConfirmedBoxes;
        }

        public int getCompletedOrders() {
            return completedOrders;
        }

        public void setCompletedOrders(int completedOrders) {
            this.completedOrders = completedOrders;
        }

        public Double getAverageLoadRatePercent() {
            return averageLoadRatePercent;
        }

        public void setAverageLoadRatePercent(Double averageLoadRatePercent) {
            this.averageLoadRatePercent = averageLoadRatePercent;
        }

        public List<WarehouseDaily> getDailyTrend() {
            return dailyTrend;
        }

        public void setDailyTrend(List<WarehouseDaily> dailyTrend) {
            this.dailyTrend = dailyTrend;
        }
    }

    public static class WarehouseDaily {
        private LocalDate date;
        private int orders;
        private long boxes;
        private int completedOrders;
        private int routes;
        private int publishedRoutes;

        public WarehouseDaily() {
        }

        public WarehouseDaily(
                LocalDate date,
                int orders,
                long boxes,
                int completedOrders,
                int routes,
                int publishedRoutes
        ) {
            this.date = date;
            this.orders = orders;
            this.boxes = boxes;
            this.completedOrders = completedOrders;
            this.routes = routes;
            this.publishedRoutes = publishedRoutes;
        }

        public LocalDate getDate() {
            return date;
        }

        public void setDate(LocalDate date) {
            this.date = date;
        }

        public int getOrders() {
            return orders;
        }

        public void setOrders(int orders) {
            this.orders = orders;
        }

        public long getBoxes() {
            return boxes;
        }

        public void setBoxes(long boxes) {
            this.boxes = boxes;
        }

        public int getCompletedOrders() {
            return completedOrders;
        }

        public void setCompletedOrders(int completedOrders) {
            this.completedOrders = completedOrders;
        }

        public int getRoutes() {
            return routes;
        }

        public void setRoutes(int routes) {
            this.routes = routes;
        }

        public int getPublishedRoutes() {
            return publishedRoutes;
        }

        public void setPublishedRoutes(int publishedRoutes) {
            this.publishedRoutes = publishedRoutes;
        }
    }

    public static class Stores {
        private LocalDate from;
        private LocalDate to;
        private List<StoreRow> stores;

        public Stores() {
        }

        public Stores(
                LocalDate from,
                LocalDate to,
                List<StoreRow> stores
        ) {
            this.from = from;
            this.to = to;
            this.stores = stores;
        }

        public LocalDate getFrom() {
            return from;
        }

        public void setFrom(LocalDate from) {
            this.from = from;
        }

        public LocalDate getTo() {
            return to;
        }

        public void setTo(LocalDate to) {
            this.to = to;
        }

        public List<StoreRow> getStores() {
            return stores;
        }

        public void setStores(List<StoreRow> stores) {
            this.stores = stores;
        }
    }

    public static class StoreRow {
        private Long storeId;
        private String storeName;
        private int orders;
        private long boxes;
        private int completedOrders;
        private int failedOrders;
        private int noSignatureAttempts;
        private long shortageBoxes;
        private long damagedBoxes;
        private long replacementRequiredBoxes;
        private Double shortageRatePercent;
        private Double damagedRatePercent;
        private List<StoreDaily> dailyTrend;
        private List<DeliveryEvent> deliveries;

        public StoreRow() {
        }

        public StoreRow(
                Long storeId,
                String storeName,
                int orders,
                long boxes,
                int completedOrders,
                int failedOrders,
                int noSignatureAttempts,
                long shortageBoxes,
                long damagedBoxes,
                long replacementRequiredBoxes,
                Double shortageRatePercent,
                Double damagedRatePercent,
                List<StoreDaily> dailyTrend,
                List<DeliveryEvent> deliveries
        ) {
            this.storeId = storeId;
            this.storeName = storeName;
            this.orders = orders;
            this.boxes = boxes;
            this.completedOrders = completedOrders;
            this.failedOrders = failedOrders;
            this.noSignatureAttempts = noSignatureAttempts;
            this.shortageBoxes = shortageBoxes;
            this.damagedBoxes = damagedBoxes;
            this.replacementRequiredBoxes = replacementRequiredBoxes;
            this.shortageRatePercent = shortageRatePercent;
            this.damagedRatePercent = damagedRatePercent;
            this.dailyTrend = dailyTrend;
            this.deliveries = deliveries;
        }

        public Long getStoreId() {
            return storeId;
        }

        public void setStoreId(Long storeId) {
            this.storeId = storeId;
        }

        public String getStoreName() {
            return storeName;
        }

        public void setStoreName(String storeName) {
            this.storeName = storeName;
        }

        public int getOrders() {
            return orders;
        }

        public void setOrders(int orders) {
            this.orders = orders;
        }

        public long getBoxes() {
            return boxes;
        }

        public void setBoxes(long boxes) {
            this.boxes = boxes;
        }

        public int getCompletedOrders() {
            return completedOrders;
        }

        public void setCompletedOrders(int completedOrders) {
            this.completedOrders = completedOrders;
        }

        public int getFailedOrders() {
            return failedOrders;
        }

        public void setFailedOrders(int failedOrders) {
            this.failedOrders = failedOrders;
        }

        public int getNoSignatureAttempts() {
            return noSignatureAttempts;
        }

        public void setNoSignatureAttempts(int noSignatureAttempts) {
            this.noSignatureAttempts = noSignatureAttempts;
        }

        public long getShortageBoxes() {
            return shortageBoxes;
        }

        public void setShortageBoxes(long shortageBoxes) {
            this.shortageBoxes = shortageBoxes;
        }

        public long getDamagedBoxes() {
            return damagedBoxes;
        }

        public void setDamagedBoxes(long damagedBoxes) {
            this.damagedBoxes = damagedBoxes;
        }

        public long getReplacementRequiredBoxes() {
            return replacementRequiredBoxes;
        }

        public void setReplacementRequiredBoxes(long replacementRequiredBoxes) {
            this.replacementRequiredBoxes = replacementRequiredBoxes;
        }

        public Double getShortageRatePercent() {
            return shortageRatePercent;
        }

        public void setShortageRatePercent(Double shortageRatePercent) {
            this.shortageRatePercent = shortageRatePercent;
        }

        public Double getDamagedRatePercent() {
            return damagedRatePercent;
        }

        public void setDamagedRatePercent(Double damagedRatePercent) {
            this.damagedRatePercent = damagedRatePercent;
        }

        public List<StoreDaily> getDailyTrend() {
            return dailyTrend;
        }

        public void setDailyTrend(List<StoreDaily> dailyTrend) {
            this.dailyTrend = dailyTrend;
        }

        public List<DeliveryEvent> getDeliveries() {
            return deliveries;
        }

        public void setDeliveries(List<DeliveryEvent> deliveries) {
            this.deliveries = deliveries;
        }
    }

    public static class StoreDaily {
        private LocalDate date;
        private int orders;
        private long boxes;
        private int completedOrders;
        private int failedOrders;
        private long shortageBoxes;
        private long damagedBoxes;
        private long replacementRequiredBoxes;

        public StoreDaily() {
        }

        public StoreDaily(
                LocalDate date,
                int orders,
                long boxes,
                int completedOrders,
                int failedOrders,
                long shortageBoxes,
                long damagedBoxes,
                long replacementRequiredBoxes
        ) {
            this.date = date;
            this.orders = orders;
            this.boxes = boxes;
            this.completedOrders = completedOrders;
            this.failedOrders = failedOrders;
            this.shortageBoxes = shortageBoxes;
            this.damagedBoxes = damagedBoxes;
            this.replacementRequiredBoxes = replacementRequiredBoxes;
        }

        public LocalDate getDate() {
            return date;
        }

        public void setDate(LocalDate date) {
            this.date = date;
        }

        public int getOrders() {
            return orders;
        }

        public void setOrders(int orders) {
            this.orders = orders;
        }

        public long getBoxes() {
            return boxes;
        }

        public void setBoxes(long boxes) {
            this.boxes = boxes;
        }

        public int getCompletedOrders() {
            return completedOrders;
        }

        public void setCompletedOrders(int completedOrders) {
            this.completedOrders = completedOrders;
        }

        public int getFailedOrders() {
            return failedOrders;
        }

        public void setFailedOrders(int failedOrders) {
            this.failedOrders = failedOrders;
        }

        public long getShortageBoxes() {
            return shortageBoxes;
        }

        public void setShortageBoxes(long shortageBoxes) {
            this.shortageBoxes = shortageBoxes;
        }

        public long getDamagedBoxes() {
            return damagedBoxes;
        }

        public void setDamagedBoxes(long damagedBoxes) {
            this.damagedBoxes = damagedBoxes;
        }

        public long getReplacementRequiredBoxes() {
            return replacementRequiredBoxes;
        }

        public void setReplacementRequiredBoxes(long replacementRequiredBoxes) {
            this.replacementRequiredBoxes = replacementRequiredBoxes;
        }
    }

    public static class DeliveryEvent {
        private Long deliveryRecordId;
        private Long orderId;
        private String orderNumber;
        private LocalDate deliveryDate;
        private LocalDateTime arrivedAt;
        private LocalDateTime deliveredAt;
        private Boolean noSignature;
        private String photoUrl;
        private Integer expectedBoxCount;
        private Integer deliveredBoxCount;
        private Integer shortageBoxCount;
        private Integer damagedBoxCount;
        private Integer replacementRequiredBoxCount;

        public DeliveryEvent() {
        }

        public DeliveryEvent(
                Long deliveryRecordId,
                Long orderId,
                String orderNumber,
                LocalDate deliveryDate,
                LocalDateTime arrivedAt,
                LocalDateTime deliveredAt,
                Boolean noSignature,
                String photoUrl,
                Integer expectedBoxCount,
                Integer deliveredBoxCount,
                Integer shortageBoxCount,
                Integer damagedBoxCount,
                Integer replacementRequiredBoxCount
        ) {
            this.deliveryRecordId = deliveryRecordId;
            this.orderId = orderId;
            this.orderNumber = orderNumber;
            this.deliveryDate = deliveryDate;
            this.arrivedAt = arrivedAt;
            this.deliveredAt = deliveredAt;
            this.noSignature = noSignature;
            this.photoUrl = photoUrl;
            this.expectedBoxCount = expectedBoxCount;
            this.deliveredBoxCount = deliveredBoxCount;
            this.shortageBoxCount = shortageBoxCount;
            this.damagedBoxCount = damagedBoxCount;
            this.replacementRequiredBoxCount = replacementRequiredBoxCount;
        }

        public Long getDeliveryRecordId() {
            return deliveryRecordId;
        }

        public void setDeliveryRecordId(Long deliveryRecordId) {
            this.deliveryRecordId = deliveryRecordId;
        }

        public Long getOrderId() {
            return orderId;
        }

        public void setOrderId(Long orderId) {
            this.orderId = orderId;
        }

        public String getOrderNumber() {
            return orderNumber;
        }

        public void setOrderNumber(String orderNumber) {
            this.orderNumber = orderNumber;
        }

        public LocalDate getDeliveryDate() {
            return deliveryDate;
        }

        public void setDeliveryDate(LocalDate deliveryDate) {
            this.deliveryDate = deliveryDate;
        }

        public LocalDateTime getArrivedAt() {
            return arrivedAt;
        }

        public void setArrivedAt(LocalDateTime arrivedAt) {
            this.arrivedAt = arrivedAt;
        }

        public LocalDateTime getDeliveredAt() {
            return deliveredAt;
        }

        public void setDeliveredAt(LocalDateTime deliveredAt) {
            this.deliveredAt = deliveredAt;
        }

        public Boolean getNoSignature() {
            return noSignature;
        }

        public void setNoSignature(Boolean noSignature) {
            this.noSignature = noSignature;
        }

        public String getPhotoUrl() {
            return photoUrl;
        }

        public void setPhotoUrl(String photoUrl) {
            this.photoUrl = photoUrl;
        }

        public Integer getExpectedBoxCount() {
            return expectedBoxCount;
        }

        public void setExpectedBoxCount(Integer expectedBoxCount) {
            this.expectedBoxCount = expectedBoxCount;
        }

        public Integer getDeliveredBoxCount() {
            return deliveredBoxCount;
        }

        public void setDeliveredBoxCount(Integer deliveredBoxCount) {
            this.deliveredBoxCount = deliveredBoxCount;
        }

        public Integer getShortageBoxCount() {
            return shortageBoxCount;
        }

        public void setShortageBoxCount(Integer shortageBoxCount) {
            this.shortageBoxCount = shortageBoxCount;
        }

        public Integer getDamagedBoxCount() {
            return damagedBoxCount;
        }

        public void setDamagedBoxCount(Integer damagedBoxCount) {
            this.damagedBoxCount = damagedBoxCount;
        }

        public Integer getReplacementRequiredBoxCount() {
            return replacementRequiredBoxCount;
        }

        public void setReplacementRequiredBoxCount(Integer replacementRequiredBoxCount) {
            this.replacementRequiredBoxCount = replacementRequiredBoxCount;
        }
    }

    public static class Exceptions {
        private LocalDate from;
        private LocalDate to;
        private int recordedCases;
        private int openCases;
        private int closedCases;
        private int noSignatureCases;
        private String coverage;
        private List<RepeatedLocation> repeatedStores;
        private List<RepeatedLocation> repeatedRoutes;
        private List<ExceptionRow> cases;

        public Exceptions() {
        }

        public Exceptions(
                LocalDate from,
                LocalDate to,
                int recordedCases,
                int openCases,
                int closedCases,
                int noSignatureCases,
                String coverage,
                List<RepeatedLocation> repeatedStores,
                List<RepeatedLocation> repeatedRoutes,
                List<ExceptionRow> cases
        ) {
            this.from = from;
            this.to = to;
            this.recordedCases = recordedCases;
            this.openCases = openCases;
            this.closedCases = closedCases;
            this.noSignatureCases = noSignatureCases;
            this.coverage = coverage;
            this.repeatedStores = repeatedStores;
            this.repeatedRoutes = repeatedRoutes;
            this.cases = cases;
        }

        public LocalDate getFrom() {
            return from;
        }

        public void setFrom(LocalDate from) {
            this.from = from;
        }

        public LocalDate getTo() {
            return to;
        }

        public void setTo(LocalDate to) {
            this.to = to;
        }

        public int getRecordedCases() {
            return recordedCases;
        }

        public void setRecordedCases(int recordedCases) {
            this.recordedCases = recordedCases;
        }

        public int getOpenCases() {
            return openCases;
        }

        public void setOpenCases(int openCases) {
            this.openCases = openCases;
        }

        public int getClosedCases() {
            return closedCases;
        }

        public void setClosedCases(int closedCases) {
            this.closedCases = closedCases;
        }

        public int getNoSignatureCases() {
            return noSignatureCases;
        }

        public void setNoSignatureCases(int noSignatureCases) {
            this.noSignatureCases = noSignatureCases;
        }

        public String getCoverage() {
            return coverage;
        }

        public void setCoverage(String coverage) {
            this.coverage = coverage;
        }

        public List<RepeatedLocation> getRepeatedStores() {
            return repeatedStores;
        }

        public void setRepeatedStores(List<RepeatedLocation> repeatedStores) {
            this.repeatedStores = repeatedStores;
        }

        public List<RepeatedLocation> getRepeatedRoutes() {
            return repeatedRoutes;
        }

        public void setRepeatedRoutes(List<RepeatedLocation> repeatedRoutes) {
            this.repeatedRoutes = repeatedRoutes;
        }

        public List<ExceptionRow> getCases() {
            return cases;
        }

        public void setCases(List<ExceptionRow> cases) {
            this.cases = cases;
        }
    }

    public static class RepeatedLocation {
        private Long id;
        private int count;

        public RepeatedLocation() {
        }

        public RepeatedLocation(
                Long id,
                int count
        ) {
            this.id = id;
            this.count = count;
        }

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public int getCount() {
            return count;
        }

        public void setCount(int count) {
            this.count = count;
        }
    }

    public static class ExceptionRow {
        private Long exceptionId;
        private ExceptionType type;
        private ExceptionStatus status;
        private LocalDateTime createdAt;
        private LocalDateTime handledAt;
        private Long resolutionMinutes;
        private String handledBy;
        private String description;
        private String resolution;
        private Long orderId;
        private String orderNumber;
        private Long warehouseId;
        private Long storeId;
        private Long driverId;
        private String driverAttributionStatus;
        private Long routeId;
        private String photoUrl;
        private String photoMatchStatus;

        public ExceptionRow() {
        }

        public ExceptionRow(
                Long exceptionId,
                ExceptionType type,
                ExceptionStatus status,
                LocalDateTime createdAt,
                LocalDateTime handledAt,
                Long resolutionMinutes,
                String handledBy,
                String description,
                String resolution,
                Long orderId,
                String orderNumber,
                Long warehouseId,
                Long storeId,
                Long driverId,
                String driverAttributionStatus,
                Long routeId,
                String photoUrl,
                String photoMatchStatus
        ) {
            this.exceptionId = exceptionId;
            this.type = type;
            this.status = status;
            this.createdAt = createdAt;
            this.handledAt = handledAt;
            this.resolutionMinutes = resolutionMinutes;
            this.handledBy = handledBy;
            this.description = description;
            this.resolution = resolution;
            this.orderId = orderId;
            this.orderNumber = orderNumber;
            this.warehouseId = warehouseId;
            this.storeId = storeId;
            this.driverId = driverId;
            this.driverAttributionStatus = driverAttributionStatus;
            this.routeId = routeId;
            this.photoUrl = photoUrl;
            this.photoMatchStatus = photoMatchStatus;
        }

        public Long getExceptionId() {
            return exceptionId;
        }

        public void setExceptionId(Long exceptionId) {
            this.exceptionId = exceptionId;
        }

        public ExceptionType getType() {
            return type;
        }

        public void setType(ExceptionType type) {
            this.type = type;
        }

        public ExceptionStatus getStatus() {
            return status;
        }

        public void setStatus(ExceptionStatus status) {
            this.status = status;
        }

        public LocalDateTime getCreatedAt() {
            return createdAt;
        }

        public void setCreatedAt(LocalDateTime createdAt) {
            this.createdAt = createdAt;
        }

        public LocalDateTime getHandledAt() {
            return handledAt;
        }

        public void setHandledAt(LocalDateTime handledAt) {
            this.handledAt = handledAt;
        }

        public Long getResolutionMinutes() {
            return resolutionMinutes;
        }

        public void setResolutionMinutes(Long resolutionMinutes) {
            this.resolutionMinutes = resolutionMinutes;
        }

        public String getHandledBy() {
            return handledBy;
        }

        public void setHandledBy(String handledBy) {
            this.handledBy = handledBy;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public String getResolution() {
            return resolution;
        }

        public void setResolution(String resolution) {
            this.resolution = resolution;
        }

        public Long getOrderId() {
            return orderId;
        }

        public void setOrderId(Long orderId) {
            this.orderId = orderId;
        }

        public String getOrderNumber() {
            return orderNumber;
        }

        public void setOrderNumber(String orderNumber) {
            this.orderNumber = orderNumber;
        }

        public Long getWarehouseId() {
            return warehouseId;
        }

        public void setWarehouseId(Long warehouseId) {
            this.warehouseId = warehouseId;
        }

        public Long getStoreId() {
            return storeId;
        }

        public void setStoreId(Long storeId) {
            this.storeId = storeId;
        }

        public Long getDriverId() {
            return driverId;
        }

        public void setDriverId(Long driverId) {
            this.driverId = driverId;
        }

        public String getDriverAttributionStatus() {
            return driverAttributionStatus;
        }

        public void setDriverAttributionStatus(String driverAttributionStatus) {
            this.driverAttributionStatus = driverAttributionStatus;
        }

        public Long getRouteId() {
            return routeId;
        }

        public void setRouteId(Long routeId) {
            this.routeId = routeId;
        }

        public String getPhotoUrl() {
            return photoUrl;
        }

        public void setPhotoUrl(String photoUrl) {
            this.photoUrl = photoUrl;
        }

        public String getPhotoMatchStatus() {
            return photoMatchStatus;
        }

        public void setPhotoMatchStatus(String photoMatchStatus) {
            this.photoMatchStatus = photoMatchStatus;
        }
    }
}
