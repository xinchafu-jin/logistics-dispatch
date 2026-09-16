package com.example.backend.service;

import com.example.backend.constants.ExceptionStatus;
import com.example.backend.constants.ExceptionType;
import com.example.backend.constants.OrderStatus;
import com.example.backend.constants.RouteStatus;
import com.example.backend.constants.ShiftType;
import com.example.backend.dao.DriversDAO;
import com.example.backend.dao.OrdersDAO;
import com.example.backend.dao.ReportReadDAO;
import com.example.backend.dao.RoutesDAO;
import com.example.backend.dao.StoresDAO;
import com.example.backend.dao.VehiclesDAO;
import com.example.backend.dao.WarehousesDAO;
import com.example.backend.dto.respones.ReportResponses;
import com.example.backend.entity.AttendanceRecordsEntity;
import com.example.backend.entity.DeliveryRecordsEntity;
import com.example.backend.entity.DriverShiftsEntity;
import com.example.backend.entity.DriversEntity;
import com.example.backend.entity.ExceptionCasesEntity;
import com.example.backend.entity.MileageLogsEntity;
import com.example.backend.entity.OrdersEntity;
import com.example.backend.entity.RoutesEntity;
import com.example.backend.entity.StoresEntity;
import com.example.backend.entity.VehiclesEntity;
import com.example.backend.entity.WarehousesEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Supervisor reports read existing records; they do not change dispatch or attendance state. */
@Service
@Transactional(readOnly = true)
public class ReportService {
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final String COMPLETION_DEFINITION =
            "COMPLETED / (CONFIRMED + IN_DELIVERY + COMPLETED + FAILED)；"
                    + "按配送日期分組，以查詢當下狀態計算；排除待確認與取消";

    private final ReportReadDAO reportReadDAO;
    private final OrdersDAO ordersDAO;
    private final RoutesDAO routesDAO;
    private final DriversDAO driversDAO;
    private final VehiclesDAO vehiclesDAO;
    private final WarehousesDAO warehousesDAO;
    private final StoresDAO storesDAO;

    public ReportService(
            ReportReadDAO reportReadDAO, OrdersDAO ordersDAO, RoutesDAO routesDAO, DriversDAO driversDAO,
            VehiclesDAO vehiclesDAO, WarehousesDAO warehousesDAO, StoresDAO storesDAO
    ) {
        this.reportReadDAO = reportReadDAO;
        this.ordersDAO = ordersDAO;
        this.routesDAO = routesDAO;
        this.driversDAO = driversDAO;
        this.vehiclesDAO = vehiclesDAO;
        this.warehousesDAO = warehousesDAO;
        this.storesDAO = storesDAO;
    }

    public static class Range {
        private final LocalDate from;
        private final LocalDate to;

        public Range(LocalDate from, LocalDate to) {
            if (from == null || to == null || from.isAfter(to)) {
                throw new IllegalArgumentException("報表起訖日期不可空白，且開始日期不能晚於結束日期");
            }
            if (ChronoUnit.DAYS.between(from, to) > 366) {
                throw new IllegalArgumentException("單次報表查詢不得超過一年，較長期間請分段查詢");
            }
            this.from = from;
            this.to = to;
        }

        public LocalDate getFrom() {
            return from;
        }

        public LocalDate getTo() {
            return to;
        }
    }

    public ReportResponses.Summary summary(Range range, Long warehouseId) {
        List<OrdersEntity> orders = reportReadDAO.orders(range.getFrom(), range.getTo(), warehouseId);
        List<RoutesEntity> routes = reportReadDAO.routes(range.getFrom(), range.getTo(), warehouseId);
        List<RoutesEntity> published = routes.stream()
                .filter(route -> route.getStatus() == RouteStatus.PUBLISHED).toList();
        List<OrdersEntity> unassigned = orders.stream()
                .filter(order -> order.getStatus() == OrderStatus.CONFIRMED && order.getRouteId() == null)
                .toList();
        int eligible = (int) orders.stream().filter(order -> completionEligible(order.getStatus())).count();
        int completed = countStatus(orders, OrderStatus.COMPLETED);

        Map<LocalDate, List<OrdersEntity>> byDate = orders.stream()
                .collect(Collectors.groupingBy(OrdersEntity::getDeliveryDate));
        List<ReportResponses.DailySummary> trend = new ArrayList<>();
        for (LocalDate day = range.getFrom(); !day.isAfter(range.getTo()); day = day.plusDays(1)) {
            List<OrdersEntity> daily = byDate.getOrDefault(day, List.of());
            int dailyEligible = (int) daily.stream().filter(order -> completionEligible(order.getStatus())).count();
            int dailyCompleted = countStatus(daily, OrderStatus.COMPLETED);
            trend.add(new ReportResponses.DailySummary(
                    day, daily.size(), boxes(daily), dailyCompleted, dailyEligible,
                    rate(dailyCompleted, dailyEligible)));
        }
        return new ReportResponses.Summary(
                range.getFrom(), range.getTo(), COMPLETION_DEFINITION,
                orders.size(), boxes(orders), distinctStores(orders),
                countStatus(orders, OrderStatus.PENDING_CONFIRM), unassigned.size(),
                (int) orders.stream().filter(order -> order.getStatus() == OrderStatus.CONFIRMED
                        && order.getRouteId() != null).count(),
                countStatus(orders, OrderStatus.IN_DELIVERY), completed,
                countStatus(orders, OrderStatus.FAILED), countStatus(orders, OrderStatus.CANCELLED),
                published.size(),
                (int) published.stream().map(RoutesEntity::getDriverId).filter(id -> id != null).distinct().count(),
                (int) published.stream().map(RoutesEntity::getVehicleId).filter(id -> id != null).distinct().count(),
                unassigned.size(), boxes(unassigned), eligible, rate(completed, eligible), trend);
    }

    public ReportResponses.Attendance attendance(Range range, Long driverId) {
        List<DriverShiftsEntity> shifts = reportReadDAO.publishedShifts(range.getFrom(), range.getTo())
                .stream().filter(shift -> driverId == null || driverId.equals(shift.getDriverId())).toList();
        Map<Long, AttendanceRecordsEntity> attendanceByShift = reportReadDAO.attendance(range.getFrom(), range.getTo())
                .stream().collect(Collectors.toMap(AttendanceRecordsEntity::getDriverShiftId, Function.identity(),
                        (first, ignored) -> first));
        Map<Long, DriversEntity> drivers = index(driversDAO.findAll(), DriversEntity::getId);
        LocalDateTime now = LocalDateTime.now(TAIPEI);

        int work = 0;
        int dayOff = 0;
        int leave = 0;
        int dueIn = 0;
        int doneIn = 0;
        int dueOut = 0;
        int doneOut = 0;
        int complete = 0;
        Set<Long> workDriverIds = new HashSet<>();
        Set<Long> dayOffDriverIds = new HashSet<>();
        Set<Long> leaveDriverIds = new HashSet<>();
        List<ReportResponses.DriverDay> clockedIn = new ArrayList<>();
        List<ReportResponses.DriverDay> missingIn = new ArrayList<>();
        List<ReportResponses.DriverDay> missingOut = new ArrayList<>();
        List<ReportResponses.AttendanceRow> rows = new ArrayList<>();

        for (DriverShiftsEntity shift : shifts) {
            if (shift.getShiftType() == ShiftType.WORK) {
                work++;
                workDriverIds.add(shift.getDriverId());
            } else if (shift.getShiftType() == ShiftType.DAY_OFF) {
                dayOff++;
                dayOffDriverIds.add(shift.getDriverId());
            } else if (shift.getShiftType() == ShiftType.LEAVE) {
                leave++;
                leaveDriverIds.add(shift.getDriverId());
            }
            AttendanceRecordsEntity punch = attendanceByShift.get(shift.getId());
            LocalDateTime start = scheduledStart(shift);
            LocalDateTime end = scheduledEnd(shift);
            LocalDateTime clockOutDeadline = clockOutDueAt(shift);
            boolean clockInDue = start != null && !now.isBefore(start);
            boolean clockOutDue = clockOutDeadline != null && !now.isBefore(clockOutDeadline);
            boolean hasIn = punch != null && punch.getClockInAt() != null;
            boolean hasOut = punch != null && punch.getClockOutAt() != null;
            String name = driverName(drivers, shift.getDriverId());
            ReportResponses.DriverDay driverDay =
                    new ReportResponses.DriverDay(shift.getDriverId(), name, shift.getWorkDate());

            if (clockInDue) {
                dueIn++;
                if (hasIn) {
                    doneIn++;
                    clockedIn.add(driverDay);
                } else {
                    missingIn.add(driverDay);
                }
            }
            if (clockOutDue) {
                dueOut++;
                if (hasOut) {
                    doneOut++;
                } else {
                    missingOut.add(driverDay);
                }
                if (hasIn && hasOut) {
                    complete++;
                }
            }
            Long inDelta = hasIn && start != null ? minutes(start, punch.getClockInAt()) : null;
            Long outDelta = hasOut && end != null ? minutes(end, punch.getClockOutAt()) : null;
            rows.add(new ReportResponses.AttendanceRow(
                    shift.getId(), shift.getDriverId(), name, shift.getWorkDate(), shift.getShiftType(),
                    start, end, clockOutDeadline, shift.getOvertimeMinutes(),
                    hasIn ? punch.getClockInAt() : null, hasOut ? punch.getClockOutAt() : null,
                    inDelta, outDelta, outDelta == null ? null : Math.max(0, outDelta),
                    hasIn && hasOut ? minutes(punch.getClockInAt(), punch.getClockOutAt()) : null,
                    punch == null ? null : punch.getBreakUsed(),
                    punch == null ? null : punch.getBreakStartedAt(),
                    punch == null ? null : punch.getBreakEndsAt(),
                    clockInDue, clockOutDue,
                    shift.getShiftType() != ShiftType.WORK ? "NOT_WORK_SHIFT"
                            : start == null || end == null ? "MISSING_SHIFT_TIME" : "OK"));
        }
        return new ReportResponses.Attendance(
                range.getFrom(), range.getTo(), work, workDriverIds.size(),
                dayOff, dayOffDriverIds.size(), leave, leaveDriverIds.size(),
                dueIn, doneIn, rate(doneIn, dueIn), dueOut, doneOut, rate(doneOut, dueOut),
                complete, rate(complete, dueOut), clockedIn, missingIn, missingOut, rows);
    }

    public ReportResponses.Routes routes(Range range, Long warehouseId, Long routeId) {
        List<RoutesEntity> allRoutes = reportReadDAO.routes(range.getFrom(), range.getTo(), null);
        List<RoutesEntity> selectedRoutes = allRoutes.stream()
                .filter(route -> warehouseId == null || warehouseId.equals(route.getWarehouseId()))
                .filter(route -> routeId == null || routeId.equals(route.getId())).toList();
        List<OrdersEntity> orders = reportReadDAO.orders(range.getFrom(), range.getTo(), warehouseId);
        Map<Long, List<OrdersEntity>> ordersByRoute = orders.stream()
                .filter(order -> order.getRouteId() != null)
                .collect(Collectors.groupingBy(OrdersEntity::getRouteId));
        Map<Long, List<DeliveryRecordsEntity>> deliveriesByOrder = deliveryByOrder(orders);
        Map<DriverDate, List<RoutesEntity>> routesByDriverDay = allRoutes.stream()
                .filter(route -> route.getDriverId() != null)
                .collect(Collectors.groupingBy(route -> new DriverDate(route.getDriverId(), route.getDate())));
        Map<DriverDate, List<MileageLogsEntity>> mileageByDriverDay = reportReadDAO.mileage(range.getFrom(), range.getTo())
                .stream().collect(Collectors.groupingBy(log -> new DriverDate(log.getDriverId(), log.getDate())));
        Map<Long, DriversEntity> drivers = index(driversDAO.findAll(), DriversEntity::getId);
        Map<Long, VehiclesEntity> vehicles = index(vehiclesDAO.findAll(), VehiclesEntity::getId);
        Map<Long, WarehousesEntity> warehouses = index(warehousesDAO.findAll(), WarehousesEntity::getId);
        Map<Long, StoresEntity> stores = index(storesDAO.findAll(), StoresEntity::getId);
        List<ReportResponses.RouteRow> rows = new ArrayList<>();

        for (RoutesEntity route : selectedRoutes) {
            List<OrdersEntity> assigned = new ArrayList<>(ordersByRoute.getOrDefault(route.getId(), List.of()));
            assigned.sort(Comparator.comparing(OrdersEntity::getSequence,
                            Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(OrdersEntity::getId));
            VehiclesEntity vehicle = vehicles.get(route.getVehicleId());
            WarehousesEntity warehouse = warehouses.get(route.getWarehouseId());
            MileageMatch match = matchMileage(route, routesByDriverDay, mileageByDriverDay);
            Double plannedKm = km(route.getTotalDistance());
            Double difference = match.getActualKm() == null || plannedKm == null
                    ? null : match.getActualKm() - plannedKm;
            Double differencePercent = difference == null || plannedKm <= 0
                    ? null : difference / plannedKm * 100;
            String comparisonStatus = match.getStatus();
            if ("READY_INFERRED_DRIVER_DATE".equals(comparisonStatus)
                    && (plannedKm == null || plannedKm <= 0)) {
                comparisonStatus = "NO_VALID_PLANNED_DISTANCE";
            }
            List<ReportResponses.RouteOrder> deliveryOrder = assigned.stream()
                    .map(order -> new ReportResponses.RouteOrder(
                            order.getId(), order.getOrderNumber(), order.getSequence(), order.getStoreId(),
                            storeName(stores, order.getStoreId()), order.getBoxCount(), order.getStatus().name()))
                    .toList();
            rows.add(new ReportResponses.RouteRow(
                    route.getId(), route.getDate(), route.getWarehouseId(),
                    warehouse == null ? null : warehouse.getName(), route.getVehicleId(),
                    vehicle == null ? null : vehicle.getPlateNumber(), route.getDriverId(),
                    driverName(drivers, route.getDriverId()), route.getStatus(),
                    vehicle == null ? null : vehicle.getCapacity(),
                    percent(route.getLoadRate()), distinctStores(assigned), assigned.size(), boxes(assigned),
                    countStatus(assigned, OrderStatus.COMPLETED), countStatus(assigned, OrderStatus.FAILED),
                    (int) assigned.stream().filter(order -> hasNoSignature(deliveriesByOrder, order.getId())).count(),
                    plannedKm, match.getActualKm(), difference, differencePercent, comparisonStatus,
                    match.getStartAt(), match.getEndAt(), match.getDurationMinutes(), deliveryOrder));
        }
        return new ReportResponses.Routes(range.getFrom(), range.getTo(),
                "CURRENT_ROUTE_RECORD_NOT_PUBLISH_SNAPSHOT", rows);
    }

    public ReportResponses.Drivers drivers(Range range, Long driverId) {
        List<DriversEntity> allDrivers = driversDAO.findAll();
        List<DriverShiftsEntity> shifts = reportReadDAO.publishedShifts(range.getFrom(), range.getTo());
        Map<Long, AttendanceRecordsEntity> attendanceByShift = reportReadDAO.attendance(range.getFrom(), range.getTo())
                .stream().collect(Collectors.toMap(AttendanceRecordsEntity::getDriverShiftId,
                        Function.identity(), (first, ignored) -> first));
        List<MileageLogsEntity> mileage = reportReadDAO.mileage(range.getFrom(), range.getTo());
        List<RoutesEntity> routes = reportReadDAO.routes(range.getFrom(), range.getTo(), null);
        Map<Long, RoutesEntity> routeById = index(routes, RoutesEntity::getId);
        List<OrdersEntity> orders = reportReadDAO.orders(range.getFrom(), range.getTo(), null);
        Map<Long, List<DeliveryRecordsEntity>> deliveryByOrder = deliveryByOrder(orders);
        LocalDateTime now = LocalDateTime.now(TAIPEI);
        List<ReportResponses.DriverRow> rows = new ArrayList<>();

        for (DriversEntity driver : allDrivers) {
            if (driverId != null && !driverId.equals(driver.getId())) {
                continue;
            }
            Long id = driver.getId();
            List<DriverShiftsEntity> workShifts = shifts.stream()
                    .filter(shift -> id.equals(shift.getDriverId()) && shift.getShiftType() == ShiftType.WORK)
                    .toList();
            List<AttendanceRecordsEntity> punches = workShifts.stream()
                    .map(shift -> attendanceByShift.get(shift.getId()))
                    .filter(punch -> punch != null).toList();
            List<MileageLogsEntity> driverMileage = mileage.stream()
                    .filter(log -> id.equals(log.getDriverId())).toList();
            List<RoutesEntity> driverRoutes = routes.stream()
                    .filter(route -> id.equals(route.getDriverId()) && route.getStatus() == RouteStatus.PUBLISHED)
                    .toList();
            List<OrdersEntity> driverOrders = orders.stream()
                    .filter(order -> id.equals(assignedDriver(order, routeById))).toList();
            List<DeliveryRecordsEntity> driverDeliveries = driverOrders.stream()
                    .flatMap(order -> deliveryByOrder.getOrDefault(order.getId(), List.of()).stream()).toList();
            List<Long> noSignatureOrderIds = driverDeliveries.stream()
                    .filter(delivery -> Boolean.TRUE.equals(delivery.getNoSignature()))
                    .map(DeliveryRecordsEntity::getOrderId).distinct().sorted().toList();
            List<AttendanceRecordsEntity> completePunches = punches.stream()
                    .filter(punch -> punch.getClockInAt() != null && punch.getClockOutAt() != null).toList();
            List<MileageLogsEntity> completeMileage = driverMileage.stream()
                    .filter(ReportService::completeMileage).toList();
            int incompleteMileage = driverMileage.size() - completeMileage.size();
            boolean duplicateMileageDate = driverMileage.stream()
                    .collect(Collectors.groupingBy(MileageLogsEntity::getDate, Collectors.counting()))
                    .values().stream().anyMatch(count -> count > 1);
            String actualMileageStatus = driverMileage.isEmpty() ? "NO_MILEAGE_LOG"
                    : duplicateMileageDate ? "MULTIPLE_MILEAGE_LOGS_FOR_DRIVER_DAY"
                    : incompleteMileage > 0 ? "INCOMPLETE_MILEAGE_LOG" : "COMPLETE";
            Double actualKm = !"COMPLETE".equals(actualMileageStatus) ? null
                    : completeMileage.stream().mapToDouble(log ->
                            log.getEndOdometer() - log.getStartOdometer()).sum();
            long afterScheduledEnd = 0;
            int missingIn = 0;
            for (DriverShiftsEntity shift : workShifts) {
                AttendanceRecordsEntity punch = attendanceByShift.get(shift.getId());
                LocalDateTime start = scheduledStart(shift);
                LocalDateTime end = scheduledEnd(shift);
                if (start != null && !now.isBefore(start)
                        && (punch == null || punch.getClockInAt() == null)) {
                    missingIn++;
                }
                if (end != null && punch != null && punch.getClockOutAt() != null) {
                    afterScheduledEnd += Math.max(0, minutes(end, punch.getClockOutAt()));
                }
            }
            Long clockSpanMinutes = completePunches.isEmpty() ? null : completePunches.stream()
                    .mapToLong(punch -> minutes(punch.getClockInAt(), punch.getClockOutAt())).sum();
            Long tripMinutes = completeMileage.isEmpty() ? null : completeMileage.stream()
                    .mapToLong(log -> minutes(log.getStartTime(), log.getEndTime())).sum();
            rows.add(new ReportResponses.DriverRow(
                    id, driver.getName(),
                    (int) workShifts.stream().map(DriverShiftsEntity::getWorkDate).distinct().count(),
                    (int) punches.stream().filter(punch -> punch.getClockInAt() != null)
                            .map(AttendanceRecordsEntity::getWorkDate).distinct().count(),
                    missingIn, clockSpanMinutes, tripMinutes,
                    (int) driverMileage.stream().filter(log -> log.getStartTime() != null).count(),
                    driverRoutes.size(), sumPlannedKm(driverRoutes), actualKm, actualMileageStatus,
                    completeMileage.size(), incompleteMileage,
                    driverOrders.size(), countStatus(driverOrders, OrderStatus.COMPLETED),
                    countStatus(driverOrders, OrderStatus.FAILED), boxes(driverOrders),
                    distinctStores(driverOrders),
                    (int) driverDeliveries.stream().filter(delivery ->
                            Boolean.TRUE.equals(delivery.getNoSignature())).count(),
                    noSignatureOrderIds, "CURRENT_ORDER_ASSIGNMENT_ONLY",
                    workShifts.stream().mapToLong(shift -> shift.getOvertimeMinutes() == null
                            ? 0 : shift.getOvertimeMinutes()).sum(), afterScheduledEnd));
        }
        rows.sort(Comparator.comparing(ReportResponses.DriverRow::getDriverId));
        return new ReportResponses.Drivers(range.getFrom(), range.getTo(), rows);
    }

    public ReportResponses.Vehicles vehicles(
            Range range, Long warehouseId, Long vehicleId, double lowLoadThresholdPercent
    ) {
        if (!Double.isFinite(lowLoadThresholdPercent) || lowLoadThresholdPercent < 0
                || lowLoadThresholdPercent > 100) {
            throw new IllegalArgumentException("低裝載率門檻必須介於 0 到 100% 之間");
        }
        List<RoutesEntity> routes = reportReadDAO.routes(range.getFrom(), range.getTo(), warehouseId);
        List<OrdersEntity> orders = reportReadDAO.orders(range.getFrom(), range.getTo(), warehouseId);
        Map<Long, List<OrdersEntity>> ordersByRoute = orders.stream()
                .filter(order -> order.getRouteId() != null)
                .collect(Collectors.groupingBy(OrdersEntity::getRouteId));
        List<ReportResponses.VehicleRow> rows = new ArrayList<>();
        for (VehiclesEntity vehicle : vehiclesDAO.findAll()) {
            if (warehouseId != null && !warehouseId.equals(vehicle.getWarehouseId())) {
                continue;
            }
            if (vehicleId != null && !vehicleId.equals(vehicle.getId())) {
                continue;
            }
            List<RoutesEntity> vehicleRoutes = routes.stream()
                    .filter(route -> vehicle.getId().equals(route.getVehicleId())).toList();
            List<OrdersEntity> vehicleOrders = vehicleRoutes.stream()
                    .flatMap(route -> ordersByRoute.getOrDefault(route.getId(), List.of()).stream()).toList();
            List<ReportResponses.VehicleRouteLoad> loads = vehicleRoutes.stream().map(route -> {
                List<OrdersEntity> assigned = ordersByRoute.getOrDefault(route.getId(), List.of());
                long boxCount = boxes(assigned);
                Double loadRate = percent(route.getLoadRate());
                return new ReportResponses.VehicleRouteLoad(
                        route.getId(), route.getDate(), route.getStatus(), boxCount, loadRate,
                        loadRate != null && loadRate < lowLoadThresholdPercent,
                        (loadRate != null && loadRate > 100)
                                || (vehicle.getCapacity() != null && boxCount > vehicle.getCapacity()));
            }).toList();
            List<RoutesEntity> publishedVehicleRoutes = vehicleRoutes.stream()
                    .filter(route -> route.getStatus() == RouteStatus.PUBLISHED).toList();
            List<Double> knownLoads = publishedVehicleRoutes.stream().map(RoutesEntity::getLoadRate)
                    .filter(load -> load != null).toList();
            rows.add(new ReportResponses.VehicleRow(
                    vehicle.getId(), vehicle.getPlateNumber(), vehicle.getWarehouseId(), vehicle.getCapacity(),
                    vehicleRoutes.size(),
                    (int) vehicleRoutes.stream().filter(route -> route.getStatus() == RouteStatus.PUBLISHED).count(),
                    vehicleRoutes.stream().map(RoutesEntity::getDate).distinct().sorted().toList(),
                    vehicleOrders.size(), boxes(vehicleOrders), distinctStores(vehicleOrders),
                    knownLoads.size() != publishedVehicleRoutes.size() || knownLoads.isEmpty()
                            ? null : knownLoads.stream().mapToDouble(Double::doubleValue)
                            .average().orElseThrow() * 100,
                    sumPlannedKm(publishedVehicleRoutes), null, "MILEAGE_LOG_HAS_NO_VEHICLE_ID", loads));
        }
        rows.sort(Comparator.comparing(ReportResponses.VehicleRow::getVehicleId));
        return new ReportResponses.Vehicles(range.getFrom(), range.getTo(), lowLoadThresholdPercent, rows);
    }

    public ReportResponses.Warehouses warehouses(Range range, Long warehouseId) {
        List<OrdersEntity> orders = reportReadDAO.orders(range.getFrom(), range.getTo(), warehouseId);
        List<RoutesEntity> routes = reportReadDAO.routes(range.getFrom(), range.getTo(), warehouseId);
        List<ReportResponses.WarehouseRow> rows = new ArrayList<>();
        for (WarehousesEntity warehouse : warehousesDAO.findAll()) {
            if (warehouseId != null && !warehouseId.equals(warehouse.getId())) {
                continue;
            }
            List<OrdersEntity> warehouseOrders = orders.stream()
                    .filter(order -> warehouse.getId().equals(order.getWarehouseId())).toList();
            List<OrdersEntity> unassigned = warehouseOrders.stream()
                    .filter(order -> order.getStatus() == OrderStatus.CONFIRMED && order.getRouteId() == null)
                    .toList();
            List<RoutesEntity> warehouseRoutes = routes.stream()
                    .filter(route -> warehouse.getId().equals(route.getWarehouseId())).toList();
            List<Double> loads = warehouseRoutes.stream()
                    .filter(route -> route.getStatus() == RouteStatus.PUBLISHED)
                    .map(RoutesEntity::getLoadRate)
                    .filter(load -> load != null).toList();
            Map<LocalDate, List<OrdersEntity>> ordersByDay = warehouseOrders.stream()
                    .collect(Collectors.groupingBy(OrdersEntity::getDeliveryDate));
            Map<LocalDate, List<RoutesEntity>> routesByDay = warehouseRoutes.stream()
                    .collect(Collectors.groupingBy(RoutesEntity::getDate));
            Set<LocalDate> activeDates = new HashSet<>(ordersByDay.keySet());
            activeDates.addAll(routesByDay.keySet());
            List<ReportResponses.WarehouseDaily> daily = activeDates.stream().sorted().map(date -> {
                List<OrdersEntity> dailyOrders = ordersByDay.getOrDefault(date, List.of());
                List<RoutesEntity> dailyRoutes = routesByDay.getOrDefault(date, List.of());
                return new ReportResponses.WarehouseDaily(
                        date, dailyOrders.size(), boxes(dailyOrders),
                        countStatus(dailyOrders, OrderStatus.COMPLETED), dailyRoutes.size(),
                        (int) dailyRoutes.stream().filter(route -> route.getStatus() == RouteStatus.PUBLISHED)
                                .count());
            }).toList();
            rows.add(new ReportResponses.WarehouseRow(
                    warehouse.getId(), warehouse.getName(), warehouseOrders.size(), boxes(warehouseOrders),
                    distinctStores(warehouseOrders), warehouseRoutes.size(),
                    (int) warehouseRoutes.stream().filter(route -> route.getStatus() == RouteStatus.PUBLISHED).count(),
                    unassigned.size(), boxes(unassigned), countStatus(warehouseOrders, OrderStatus.COMPLETED),
                    loads.isEmpty() || loads.size() != warehouseRoutes.stream()
                            .filter(route -> route.getStatus() == RouteStatus.PUBLISHED).count()
                            ? null : loads.stream().mapToDouble(Double::doubleValue)
                            .average().orElseThrow() * 100, daily));
        }
        rows.sort(Comparator.comparing(ReportResponses.WarehouseRow::getWarehouseId));
        return new ReportResponses.Warehouses(range.getFrom(), range.getTo(), rows);
    }

    public ReportResponses.Stores stores(Range range, Long warehouseId, Long storeId) {
        List<OrdersEntity> orders = reportReadDAO.orders(range.getFrom(), range.getTo(), warehouseId);
        Map<Long, List<OrdersEntity>> ordersByStore = orders.stream()
                .collect(Collectors.groupingBy(OrdersEntity::getStoreId));
        Map<Long, List<DeliveryRecordsEntity>> deliveriesByOrder = deliveryByOrder(orders);
        List<ReportResponses.StoreRow> rows = new ArrayList<>();
        for (StoresEntity store : storesDAO.findAll()) {
            if (storeId != null && !storeId.equals(store.getId())) {
                continue;
            }
            List<OrdersEntity> storeOrders = ordersByStore.getOrDefault(store.getId(), List.of());
            if (warehouseId != null && storeOrders.isEmpty()) {
                continue;
            }
            Map<Long, OrdersEntity> orderById = index(storeOrders, OrdersEntity::getId);
            List<DeliveryRecordsEntity> deliveries = storeOrders.stream()
                    .flatMap(order -> deliveriesByOrder.getOrDefault(order.getId(), List.of()).stream())
                    .sorted(Comparator.comparing(DeliveryRecordsEntity::getId)).toList();
            Map<LocalDate, List<OrdersEntity>> ordersByDay = storeOrders.stream()
                    .collect(Collectors.groupingBy(OrdersEntity::getDeliveryDate));
            List<ReportResponses.StoreDaily> daily = ordersByDay.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(entry -> new ReportResponses.StoreDaily(
                            entry.getKey(), entry.getValue().size(), boxes(entry.getValue()),
                            countStatus(entry.getValue(), OrderStatus.COMPLETED),
                            countStatus(entry.getValue(), OrderStatus.FAILED)))
                    .toList();
            List<ReportResponses.DeliveryEvent> events = deliveries.stream().map(delivery -> {
                OrdersEntity order = orderById.get(delivery.getOrderId());
                return new ReportResponses.DeliveryEvent(
                        delivery.getId(), delivery.getOrderId(), order.getOrderNumber(),
                        order.getDeliveryDate(), delivery.getArrivedAt(), delivery.getDeliveredAt(),
                        delivery.getNoSignature(), delivery.getPhotoUrl());
            }).toList();
            rows.add(new ReportResponses.StoreRow(
                    store.getId(), store.getName(), storeOrders.size(), boxes(storeOrders),
                    countStatus(storeOrders, OrderStatus.COMPLETED),
                    countStatus(storeOrders, OrderStatus.FAILED),
                    (int) deliveries.stream().filter(delivery ->
                            Boolean.TRUE.equals(delivery.getNoSignature())).count(), daily, events));
        }
        rows.sort(Comparator.comparing(ReportResponses.StoreRow::getStoreId));
        return new ReportResponses.Stores(range.getFrom(), range.getTo(), rows);
    }

    public ReportResponses.Exceptions exceptions(
            Range range, ExceptionType type, ExceptionStatus status,
            Long warehouseId, Long storeId, Long driverId, Long routeId
    ) {
        List<ExceptionCasesEntity> cases = reportReadDAO.exceptions(range.getFrom(), range.getTo());
        List<Long> orderIds = cases.stream().map(ExceptionCasesEntity::getOrderId)
                .filter(id -> id != null).distinct().toList();
        Map<Long, OrdersEntity> ordersById = index(ordersDAO.findAllById(orderIds), OrdersEntity::getId);
        List<Long> routeIds = ordersById.values().stream().map(OrdersEntity::getRouteId)
                .filter(id -> id != null).distinct().toList();
        Map<Long, RoutesEntity> routesById = index(routesDAO.findAllById(routeIds), RoutesEntity::getId);
        Map<Long, List<DeliveryRecordsEntity>> deliveriesByOrder = reportReadDAO.deliveriesForOrders(orderIds)
                .stream().collect(Collectors.groupingBy(DeliveryRecordsEntity::getOrderId));
        List<ReportResponses.ExceptionRow> rows = new ArrayList<>();

        for (ExceptionCasesEntity exceptionCase : cases) {
            if ((type != null && exceptionCase.getType() != type)
                    || (status != null && exceptionCase.getStatus() != status)) {
                continue;
            }
            OrdersEntity order = ordersById.get(exceptionCase.getOrderId());
            Long currentDriverId = order == null ? null : assignedDriver(order, routesById);
            if ((warehouseId != null && (order == null || !warehouseId.equals(order.getWarehouseId())))
                    || (storeId != null && (order == null || !storeId.equals(order.getStoreId())))
                    || (driverId != null && !driverId.equals(currentDriverId))
                    || (routeId != null && (order == null || !routeId.equals(order.getRouteId())))) {
                continue;
            }
            List<DeliveryRecordsEntity> noSignatureAttempts = exceptionCase.getType() == ExceptionType.NO_SIGNATURE
                    ? deliveriesByOrder.getOrDefault(exceptionCase.getOrderId(), List.of()).stream()
                    .filter(delivery -> Boolean.TRUE.equals(delivery.getNoSignature())).toList()
                    : List.of();
            String photoStatus;
            if (exceptionCase.getType() != ExceptionType.NO_SIGNATURE) {
                photoStatus = "NOT_APPLICABLE";
            } else if (noSignatureAttempts.isEmpty()) {
                photoStatus = "NO_PHOTO_RECORD";
            } else if (noSignatureAttempts.size() > 1) {
                photoStatus = "AMBIGUOUS_MULTIPLE_ATTEMPTS";
            } else {
                photoStatus = noSignatureAttempts.getFirst().getPhotoUrl() == null
                        ? "PHOTO_NOT_RECORDED" : "MATCHED_BY_ORDER";
            }
            String photoUrl = noSignatureAttempts.size() == 1
                    ? noSignatureAttempts.getFirst().getPhotoUrl() : null;
            Long resolutionMinutes = exceptionCase.getStatus() == ExceptionStatus.CLOSED
                    && exceptionCase.getHandledAt() != null
                    && !exceptionCase.getHandledAt().isBefore(exceptionCase.getCreatedAt())
                    ? minutes(exceptionCase.getCreatedAt(), exceptionCase.getHandledAt()) : null;
            rows.add(new ReportResponses.ExceptionRow(
                    exceptionCase.getId(), exceptionCase.getType(), exceptionCase.getStatus(),
                    exceptionCase.getCreatedAt(), exceptionCase.getHandledAt(), resolutionMinutes,
                    exceptionCase.getHandledBy(), exceptionCase.getDescription(), exceptionCase.getResolution(),
                    exceptionCase.getOrderId(), order == null ? null : order.getOrderNumber(),
                    order == null ? null : order.getWarehouseId(),
                    order == null ? null : order.getStoreId(),
                    currentDriverId,
                    currentDriverId == null ? "UNKNOWN" : "CURRENT_ORDER_OR_ROUTE_ASSIGNMENT_ONLY",
                    order == null ? null : order.getRouteId(), photoUrl, photoStatus));
        }
        return new ReportResponses.Exceptions(
                range.getFrom(), range.getTo(), rows.size(),
                (int) rows.stream().filter(row -> row.getStatus() == ExceptionStatus.OPEN).count(),
                (int) rows.stream().filter(row -> row.getStatus() == ExceptionStatus.CLOSED).count(),
                (int) rows.stream().filter(row -> row.getType() == ExceptionType.NO_SIGNATURE).count(),
                "僅統計資料庫已有的異常；一般司機異常回報與結案流程尚未完整實作",
                repeated(rows, ReportResponses.ExceptionRow::getStoreId),
                repeated(rows, ReportResponses.ExceptionRow::getRouteId), rows);
    }

    private static class DriverDate {
        private final Long driverId;
        private final LocalDate date;

        private DriverDate(Long driverId, LocalDate date) {
            this.driverId = driverId;
            this.date = date;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof DriverDate that)) {
                return false;
            }
            return Objects.equals(driverId, that.driverId) && Objects.equals(date, that.date);
        }

        @Override
        public int hashCode() {
            return Objects.hash(driverId, date);
        }
    }

    private static class MileageMatch {
        private final Double actualKm;
        private final LocalDateTime startAt;
        private final LocalDateTime endAt;
        private final Long durationMinutes;
        private final String status;

        private MileageMatch(Double actualKm, LocalDateTime startAt, LocalDateTime endAt,
                             Long durationMinutes, String status) {
            this.actualKm = actualKm;
            this.startAt = startAt;
            this.endAt = endAt;
            this.durationMinutes = durationMinutes;
            this.status = status;
        }

        private Double getActualKm() {
            return actualKm;
        }

        private LocalDateTime getStartAt() {
            return startAt;
        }

        private LocalDateTime getEndAt() {
            return endAt;
        }

        private Long getDurationMinutes() {
            return durationMinutes;
        }

        private String getStatus() {
            return status;
        }
    }

    private static MileageMatch matchMileage(
            RoutesEntity route, Map<DriverDate, List<RoutesEntity>> routesByDriverDay,
            Map<DriverDate, List<MileageLogsEntity>> mileageByDriverDay
    ) {
        if (route.getStatus() != RouteStatus.PUBLISHED) {
            return new MileageMatch(null, null, null, null, "ROUTE_NOT_PUBLISHED");
        }
        if (route.getDriverId() == null) {
            return new MileageMatch(null, null, null, null, "NO_DRIVER");
        }
        DriverDate key = new DriverDate(route.getDriverId(), route.getDate());
        if (routesByDriverDay.getOrDefault(key, List.of()).size() != 1) {
            return new MileageMatch(null, null, null, null, "MULTIPLE_ROUTES_FOR_DRIVER_DAY");
        }
        List<MileageLogsEntity> logs = mileageByDriverDay.getOrDefault(key, List.of());
        if (logs.isEmpty()) {
            return new MileageMatch(null, null, null, null, "NO_MILEAGE_LOG");
        }
        if (logs.size() != 1) {
            return new MileageMatch(null, null, null, null, "MULTIPLE_MILEAGE_LOGS");
        }
        MileageLogsEntity log = logs.getFirst();
        LocalDateTime start = log.getStartTime();
        LocalDateTime end = log.getEndTime();
        Long duration = start != null && end != null && !end.isBefore(start) ? minutes(start, end) : null;
        if (!completeMileage(log)) {
            return new MileageMatch(null, start, end, duration, "INCOMPLETE_MILEAGE_LOG");
        }
        return new MileageMatch((double) (log.getEndOdometer() - log.getStartOdometer()),
                start, end, duration, "READY_INFERRED_DRIVER_DATE");
    }

    private static boolean completeMileage(MileageLogsEntity log) {
        return log.getStartOdometer() != null && log.getEndOdometer() != null
                && log.getEndOdometer() >= log.getStartOdometer()
                && log.getStartTime() != null && log.getEndTime() != null
                && !log.getEndTime().isBefore(log.getStartTime());
    }

    private Map<Long, List<DeliveryRecordsEntity>> deliveryByOrder(List<OrdersEntity> orders) {
        return reportReadDAO.deliveriesForOrders(orders.stream().map(OrdersEntity::getId).toList())
                .stream().collect(Collectors.groupingBy(DeliveryRecordsEntity::getOrderId));
    }

    private static boolean hasNoSignature(
            Map<Long, List<DeliveryRecordsEntity>> deliveriesByOrder, Long orderId
    ) {
        return deliveriesByOrder.getOrDefault(orderId, List.of()).stream()
                .anyMatch(delivery -> Boolean.TRUE.equals(delivery.getNoSignature()));
    }

    private static Long assignedDriver(OrdersEntity order, Map<Long, RoutesEntity> routesById) {
        if (order.getAssignedDriverId() != null) {
            return order.getAssignedDriverId();
        }
        RoutesEntity route = routesById.get(order.getRouteId());
        return route == null ? null : route.getDriverId();
    }

    private static LocalDateTime scheduledStart(DriverShiftsEntity shift) {
        return shift.getShiftType() == ShiftType.WORK && shift.getWorkStart() != null
                ? LocalDateTime.of(shift.getWorkDate(), shift.getWorkStart()) : null;
    }

    private static LocalDateTime scheduledEnd(DriverShiftsEntity shift) {
        if (shift.getShiftType() != ShiftType.WORK || shift.getWorkStart() == null
                || shift.getWorkEnd() == null) {
            return null;
        }
        LocalDateTime end = LocalDateTime.of(shift.getWorkDate(), shift.getWorkEnd());
        if (!shift.getWorkEnd().isAfter(shift.getWorkStart())) {
            end = end.plusDays(1);
        }
        return end;
    }

    private static LocalDateTime clockOutDueAt(DriverShiftsEntity shift) {
        LocalDateTime end = scheduledEnd(shift);
        return end == null ? null : end.plusMinutes(Math.max(0, shift.getOvertimeMinutes() == null
                ? 0 : shift.getOvertimeMinutes()));
    }

    private static long minutes(LocalDateTime start, LocalDateTime end) {
        return Duration.between(start, end).toMinutes();
    }

    private static int countStatus(List<OrdersEntity> orders, OrderStatus status) {
        return (int) orders.stream().filter(order -> order.getStatus() == status).count();
    }

    private static boolean completionEligible(OrderStatus status) {
        return status == OrderStatus.CONFIRMED || status == OrderStatus.IN_DELIVERY
                || status == OrderStatus.COMPLETED || status == OrderStatus.FAILED;
    }

    private static long boxes(List<OrdersEntity> orders) {
        return orders.stream().mapToLong(order -> order.getBoxCount() == null ? 0 : order.getBoxCount()).sum();
    }

    private static int distinctStores(List<OrdersEntity> orders) {
        return (int) orders.stream().map(OrdersEntity::getStoreId).filter(id -> id != null).distinct().count();
    }

    private static Double rate(int numerator, int denominator) {
        return denominator == 0 ? null : numerator * 100.0 / denominator;
    }

    private static Double percent(Double ratio) {
        return ratio == null ? null : ratio * 100;
    }

    private static Double km(Double meters) {
        return meters == null ? null : meters / 1000;
    }

    private static Double sumPlannedKm(List<RoutesEntity> routes) {
        if (routes.stream().anyMatch(route -> route.getTotalDistance() == null)) {
            return null;
        }
        return routes.stream().mapToDouble(RoutesEntity::getTotalDistance).sum() / 1000;
    }

    private static String driverName(Map<Long, DriversEntity> drivers, Long driverId) {
        DriversEntity driver = drivers.get(driverId);
        return driver == null ? null : driver.getName();
    }

    private static String storeName(Map<Long, StoresEntity> stores, Long storeId) {
        StoresEntity store = stores.get(storeId);
        return store == null ? null : store.getName();
    }

    private static <T> Map<Long, T> index(Iterable<T> values, Function<T, Long> id) {
        Map<Long, T> result = new HashMap<>();
        for (T value : values) {
            result.put(id.apply(value), value);
        }
        return result;
    }

    private static List<ReportResponses.RepeatedLocation> repeated(
            List<ReportResponses.ExceptionRow> rows, Function<ReportResponses.ExceptionRow, Long> id
    ) {
        Map<Long, Integer> counts = new HashMap<>();
        for (ReportResponses.ExceptionRow row : rows) {
            Long value = id.apply(row);
            if (value != null) {
                counts.merge(value, 1, Integer::sum);
            }
        }
        return counts.entrySet().stream().filter(entry -> entry.getValue() >= 2)
                .sorted(Comparator.<Map.Entry<Long, Integer>>comparingInt(Map.Entry::getValue)
                        .reversed().thenComparing(Map.Entry::getKey))
                .map(entry -> new ReportResponses.RepeatedLocation(entry.getKey(), entry.getValue()))
                .toList();
    }
}
