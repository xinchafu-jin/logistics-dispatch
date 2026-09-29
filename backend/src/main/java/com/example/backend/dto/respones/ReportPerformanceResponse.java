package com.example.backend.dto.respones;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** Read-only operational results. Percentages are 0..100; missing denominators remain null. */
public record ReportPerformanceResponse(
        LocalDate from, LocalDate to, Workforce workforce, Fleet fleet,
        List<WarehousePerformance> warehouses, List<AttendanceDetail> shifts, List<TripDetail> trips) {
    public record Workforce(int scheduledWorkShifts, int excusedFullDayShifts, int dueShifts,
            int attendedShifts, int onTimeShifts, int lateShifts, int missingClockInShifts,
            int finishedShifts, int overtimeShifts, long overtimeMinutes, int missingTimeShifts,
            Double attendanceRate, Double onTimeRate, Double overtimeRate) {}
    public record Fleet(int startedTrips, int returnedTrips, int openTrips, int invalidTrips,
            int usedVehicles, int distanceRecordedTrips, Double actualKm, Double returnRate) {}
    public record WarehousePerformance(Long warehouseId, String warehouseName, Workforce workforce, Fleet fleet) {}
    public record AttendanceDetail(Long shiftId, Long driverId, String driverName, Long warehouseId,
            String warehouseName, LocalDate workDate, LocalDateTime expectedStartAt,
            LocalDateTime clockInAt, LocalDateTime scheduledEndAt, LocalDateTime clockOutAt,
            String attendanceStatus, boolean due, Boolean onTime, Long overtimeMinutes) {}
    public record TripDetail(Long mileageId, Long routeId, Long driverId, Long vehicleId,
            String plateNumber, Long warehouseId, String warehouseName, LocalDate date,
            LocalDateTime startAt, LocalDateTime endAt, String status, Double actualKm, String distanceSource) {}
}
