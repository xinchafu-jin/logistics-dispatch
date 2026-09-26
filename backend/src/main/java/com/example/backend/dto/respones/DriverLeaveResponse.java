package com.example.backend.dto.respones;

import com.example.backend.constants.LeaveRequestStatus;
import com.example.backend.constants.LeaveRequestMode;
import com.example.backend.constants.LeaveSubmissionSource;
import com.example.backend.constants.LeaveType;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

public record DriverLeaveResponse(
        Long id,
        String batchId,
        LeaveRequestMode requestMode,
        Long driverId,
        String driverName,
        Long driverShiftId,
        LocalDate workDate,
        LeaveType requestedLeaveType,
        LeaveType leaveType,
        boolean fullDay,
        LocalTime leaveStart,
        LocalTime leaveEnd,
        String requestReason,
        String evidencePhotoUrl,
        LeaveRequestStatus status,
        LeaveSubmissionSource submissionSource,
        String decisionReason,
        LocalDateTime requestedAt,
        Long reviewedByAdminId,
        String reviewedBy,
        LocalDateTime reviewedAt,
        String typeChangeReason,
        LocalDateTime typeChangedAt,
        String typeChangedBy,
        LocalDateTime driverReadAt,
        LocalDateTime lastUpdatedAt
) {
}
