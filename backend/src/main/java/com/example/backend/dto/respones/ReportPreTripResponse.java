package com.example.backend.dto.respones;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** Every submitted inspection is retained, including failed attempts and withdrawn records. */
public record ReportPreTripResponse(LocalDate from, LocalDate to, List<Inspection> inspections) {
    public record Inspection(
            Long inspectionId, LocalDate workDate, LocalDateTime submittedAt,
            Long driverId, String driverName, String driverAccount,
            Long vehicleId, String plateNumber, Long warehouseId, String warehouseName,
            Long routeId, Integer routeVersion, BigDecimal alcoholMgL, Boolean passed,
            LocalDateTime invalidatedAt, String note,
            boolean hasAlcoholPhoto, boolean hasFaultPhoto,
            List<Check> checks, List<String> abnormalItems) {
    }

    /** null means the historic result was not recorded; it must not be treated as normal. */
    public record Check(String key, String group, String label, Boolean normal) {
    }
}
