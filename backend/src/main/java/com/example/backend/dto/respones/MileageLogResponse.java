package com.example.backend.dto.respones;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record MileageLogResponse(
        Long id,
        Long driverId,
        LocalDate date,
        Integer startOdometer,
        Integer endOdometer,
        LocalDateTime startTime,
        LocalDateTime endTime,
        Integer actualDistance,
        Long actualDurationMinutes
) {
}
