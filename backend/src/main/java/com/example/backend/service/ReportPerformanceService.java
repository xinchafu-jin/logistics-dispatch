package com.example.backend.service;

import com.example.backend.constants.ShiftType;
import com.example.backend.dao.*;
import com.example.backend.dto.respones.ReportPerformanceResponse;
import com.example.backend.dto.respones.ReportPerformanceResponse.*;
import com.example.backend.dto.respones.ReportResponses;
import com.example.backend.entity.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Attendance is attributed to current driver affiliation; trips to their recorded departure route. */
@Service
@Transactional(readOnly = true)
public class ReportPerformanceService {
    private final ReportService reports;
    private final ReportReadDAO reads;
    private final DriversDAO drivers;
    private final VehiclesDAO vehicles;
    private final WarehousesDAO warehouses;

    public ReportPerformanceService(ReportService reports, ReportReadDAO reads, DriversDAO drivers,
            VehiclesDAO vehicles, WarehousesDAO warehouses) {
        this.reports = reports; this.reads = reads; this.drivers = drivers;
        this.vehicles = vehicles; this.warehouses = warehouses;
    }

    public ReportPerformanceResponse performance(ReportService.Range range, Long warehouseId, Long driverId, Long vehicleId) {
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Taipei"));
        Map<Long, DriversEntity> driverById = drivers.findAll().stream().collect(Collectors.toMap(DriversEntity::getId, Function.identity()));
        Map<Long, VehiclesEntity> vehicleById = vehicles.findAll().stream().collect(Collectors.toMap(VehiclesEntity::getId, Function.identity()));
        Map<Long, String> names = warehouses.findAll().stream().collect(Collectors.toMap(WarehousesEntity::getId, WarehousesEntity::getName));
        Map<Long, List<DriverLeaveRequestsEntity>> approvedLeaves = reads.approvedLeaves(range.getFrom(), range.getTo()).stream()
                .collect(Collectors.groupingBy(DriverLeaveRequestsEntity::getDriverShiftId));
        List<ReportResponses.AttendanceRow> workRows = reports.attendance(range, driverId).getShifts().stream()
                .filter(row -> row.getShiftType() == ShiftType.WORK)
                .filter(row -> warehouseId == null || (driverById.containsKey(row.getDriverId())
                        && warehouseId.equals(driverById.get(row.getDriverId()).getWarehouseId()))).toList();
        List<AttendanceDetail> attendance = workRows.stream().map(row -> {
            DriversEntity driver = driverById.get(row.getDriverId());
            Long wh = driver == null ? null : driver.getWarehouseId();
            List<DriverLeaveRequestsEntity> leaves = approvedLeaves.getOrDefault(row.getShiftId(), List.of());
            boolean excused = leaves.stream().anyMatch(leave -> Boolean.TRUE.equals(leave.getFullDay()));
            LocalDateTime expected = row.getScheduledStartAt();
            // An approved partial leave covering the start moves the expected arrival time, not the roster.
            for (var leave : leaves.stream().filter(l -> !Boolean.TRUE.equals(l.getFullDay())
                    && l.getLeaveStart() != null && l.getLeaveEnd() != null).sorted(Comparator.comparing(DriverLeaveRequestsEntity::getLeaveStart)).toList()) {
                LocalDateTime from = row.getWorkDate().atTime(leave.getLeaveStart());
                LocalDateTime to = row.getWorkDate().atTime(leave.getLeaveEnd());
                if (!to.isAfter(from)) to = to.plusDays(1);
                if (expected != null && !from.isAfter(expected) && to.isAfter(expected)) expected = to;
            }
            boolean invalid = expected == null || row.getScheduledEndAt() == null;
            boolean due = !excused && !invalid && !now.isBefore(expected);
            LocalDateTime in = row.getClockInAt(), out = row.getClockOutAt();
            boolean validIn = in != null && !in.isAfter(now);
            Boolean onTime = due && validIn ? !in.isAfter(expected) : null;
            Long overtime = due && validIn ? RecordedOvertime.minutes(in, out, row.getScheduledEndAt(),
                    row.getBreakStartedAt(), row.getBreakExpectedEndAt(), now) : null;
            String state = excused ? "APPROVED_FULL_DAY_LEAVE" : invalid ? "MISSING_SHIFT_TIME"
                    : !due ? "NOT_DUE" : !validIn ? "MISSING_CLOCK_IN" : Boolean.TRUE.equals(onTime) ? "ON_TIME" : "LATE";
            return new AttendanceDetail(row.getShiftId(), row.getDriverId(), row.getDriverName(), wh,
                    names.getOrDefault(wh, "未設定倉庫"), row.getWorkDate(), expected, in,
                    row.getScheduledEndAt(), out, state, due, onTime, overtime);
        }).toList();

        List<RoutesEntity> allRoutes = reads.routes(range.getFrom(), range.getTo(), null);
        Map<Long, RoutesEntity> routeById = allRoutes.stream().collect(Collectors.toMap(RoutesEntity::getId, Function.identity()));
        List<TripDetail> trips = new ArrayList<>();
        for (var log : reads.mileage(range.getFrom(), range.getTo())) {
            if (log.getStartTime() == null || log.getStartTime().isAfter(now)) continue;
            if (driverId != null && !driverId.equals(log.getDriverId())) continue;
            RoutesEntity route = log.getRouteId() == null ? null : routeById.get(log.getRouteId());
            if (route == null && log.getRouteId() == null) {
                List<RoutesEntity> candidates = allRoutes.stream().filter(r -> Objects.equals(r.getDate(), log.getDate())
                        && Objects.equals(r.getDriverId(), log.getDriverId())
                        && (log.getVehicleId() == null || Objects.equals(r.getVehicleId(), log.getVehicleId()))).toList();
                if (candidates.size() == 1) route = candidates.getFirst();
            }
            Long v = log.getVehicleId() != null ? log.getVehicleId() : route == null ? null : route.getVehicleId();
            Long wh = route == null ? null : route.getWarehouseId();
            if (warehouseId != null && !warehouseId.equals(wh)) continue;
            if (vehicleId != null && !vehicleId.equals(v)) continue;
            LocalDateTime end = log.getEndTime();
            String state = end == null ? "OPEN" : end.isBefore(log.getStartTime()) || end.isAfter(now) ? "INVALID" : "RETURNED";
            Double km = null; String source = "MISSING";
            if ("RETURNED".equals(state)) {
                if (log.getMileageSettledAt() != null && log.getGpsDistanceKm() != null
                        && Double.isFinite(log.getGpsDistanceKm()) && log.getGpsDistanceKm() >= 0) {
                    km = log.getGpsDistanceKm(); source = "GPS_SETTLED";
                } else if (log.getMileageSettledAt() == null && log.getGpsDistanceStatus() == null
                        && log.getStartOdometer() != null && log.getEndOdometer() != null
                        && log.getEndOdometer() >= log.getStartOdometer()) {
                    km = (double) log.getEndOdometer() - log.getStartOdometer(); source = "LEGACY_ODOMETER";
                }
            }
            VehiclesEntity vehicle = vehicleById.get(v);
            trips.add(new TripDetail(log.getId(), log.getRouteId() != null ? log.getRouteId() : route == null ? null : route.getId(),
                    log.getDriverId(), v, vehicle == null ? "未綁定車輛" : vehicle.getPlateNumber(), wh,
                    names.getOrDefault(wh, "無法確認出貨倉庫"), log.getDate(), log.getStartTime(), end, state, km, source));
        }
        List<WarehousePerformance> byWarehouse = names.entrySet().stream()
                .filter(entry -> warehouseId == null || warehouseId.equals(entry.getKey())).sorted(Map.Entry.comparingByKey())
                .map(entry -> new WarehousePerformance(entry.getKey(), entry.getValue(), workforce(attendance.stream()
                        .filter(row -> entry.getKey().equals(row.warehouseId())).toList()), fleet(trips.stream()
                        .filter(row -> entry.getKey().equals(row.warehouseId())).toList()))).collect(Collectors.toCollection(ArrayList::new));
        if (warehouseId == null && (attendance.stream().anyMatch(row -> row.warehouseId() == null)
                || trips.stream().anyMatch(row -> row.warehouseId() == null))) {
            byWarehouse.add(new WarehousePerformance(null, "未歸屬／無法確認倉庫", workforce(attendance.stream()
                    .filter(row -> row.warehouseId() == null).toList()), fleet(trips.stream().filter(row -> row.warehouseId() == null).toList())));
        }
        return new ReportPerformanceResponse(range.getFrom(), range.getTo(), workforce(attendance), fleet(trips), byWarehouse, attendance, trips);
    }

    static Workforce workforce(List<AttendanceDetail> rows) {
        int excused = 0, due = 0, attended = 0, onTime = 0, finished = 0, overtime = 0, missingTime = 0;
        long overtimeMinutes = 0;
        for (var row : rows) {
            if ("APPROVED_FULL_DAY_LEAVE".equals(row.attendanceStatus())) excused++;
            if ("MISSING_SHIFT_TIME".equals(row.attendanceStatus())) missingTime++;
            if (!row.due()) continue;
            due++;
            if (row.onTime() != null) { attended++; if (row.onTime()) onTime++; }
            if (row.overtimeMinutes() != null) {
                finished++; overtimeMinutes += row.overtimeMinutes();
                if (row.overtimeMinutes() > 0) overtime++;
            }
        }
        return new Workforce(rows.size(), excused, due, attended, onTime, attended - onTime, due - attended,
                finished, overtime, overtimeMinutes, missingTime, rate(attended, due), rate(onTime, attended), rate(overtime, finished));
    }
    static Fleet fleet(List<TripDetail> rows) {
        int returned = 0, open = 0, invalid = 0, recorded = 0; double km = 0;
        Set<Long> used = new HashSet<>();
        for (var row : rows) {
            if (row.vehicleId() != null) used.add(row.vehicleId());
            if ("RETURNED".equals(row.status())) returned++;
            else if ("OPEN".equals(row.status())) open++;
            else invalid++;
            if (row.actualKm() != null) { recorded++; km += row.actualKm(); }
        }
        return new Fleet(rows.size(), returned, open, invalid, used.size(), recorded, recorded > 0 ? km : null, rate(returned, rows.size()));
    }
    private static Double rate(int numerator, int denominator) { return denominator == 0 ? null : numerator * 100.0 / denominator; }
}
