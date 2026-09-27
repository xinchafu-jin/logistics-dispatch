package com.example.backend.dto.respones;

import java.time.LocalDateTime;
import java.util.List;

public record VehicleMaintenanceSummary(
        Integer currentOdometerKm, Integer minorRemainingKm, Integer majorRemainingKm,
        Integer retirementRemainingKm, Integer warningKm, Double plannedKm,
        Double projectedMinorKm, Double projectedMajorKm, Double projectedRetirementKm,
        String decision, List<String> reasons, long minorCount, long majorCount, long repairCount,
        LocalDateTime lastMinorAt, LocalDateTime lastMajorAt, LocalDateTime lastRepairAt) {}
