package com.example.backend.dto.respones;

import com.example.backend.constants.LeaveRequestStatus;
import com.example.backend.constants.LeaveType;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 主管畫面的一張群組申請；同假別、多日期共用一次審核結果。 */
public record DriverLeaveBatchResponse(
        String batchId,
        Long driverId,
        String driverName,
        LeaveType leaveType,
        List<LocalDate> workDates,
        String requestReason,
        LeaveRequestStatus status,
        String decisionReason,
        LocalDateTime requestedAt,
        String reviewedBy,
        LocalDateTime reviewedAt,
        List<DriverLeaveResponse> items
) {
}
