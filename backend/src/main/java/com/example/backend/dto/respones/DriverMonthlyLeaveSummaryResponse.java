package com.example.backend.dto.respones;

import java.time.YearMonth;
import java.util.List;

public record DriverMonthlyLeaveSummaryResponse(
        Long driverId,
        String driverName,
        YearMonth month,
        boolean hasLeaveRecords,
        String emptyMessage,
        List<DriverLeaveResponse> records
) {
}
