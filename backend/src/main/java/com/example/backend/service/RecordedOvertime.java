package com.example.backend.service;

import java.time.Duration;
import java.time.LocalDateTime;

/** Read-only observed minutes, not payroll approval or the live attendance 30-minute policy. */
final class RecordedOvertime {
    private RecordedOvertime() {}

    static Long minutes(LocalDateTime in, LocalDateTime out, LocalDateTime scheduledEnd,
            LocalDateTime breakStart, LocalDateTime breakEnd, LocalDateTime asOf) {
        if (in == null || out == null || scheduledEnd == null || in.isAfter(asOf)
                || out.isAfter(asOf) || out.isBefore(in)) return null;
        LocalDateTime start = in.isAfter(scheduledEnd) ? in : scheduledEnd;
        if (!out.isAfter(start)) return 0L;
        long seconds = Duration.between(start, out).getSeconds();
        if (breakStart != null && breakEnd != null && breakEnd.isAfter(breakStart)) {
            LocalDateTime overlapStart = breakStart.isAfter(start) ? breakStart : start;
            LocalDateTime overlapEnd = breakEnd.isBefore(out) ? breakEnd : out;
            if (overlapEnd.isAfter(overlapStart)) seconds -= Duration.between(overlapStart, overlapEnd).getSeconds();
        }
        return Math.max(0, seconds) / 60;
    }
}
