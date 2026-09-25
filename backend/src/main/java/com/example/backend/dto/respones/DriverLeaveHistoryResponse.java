package com.example.backend.dto.respones;

import com.example.backend.constants.LeaveRequestEventType;
import com.example.backend.constants.LeaveRequestStatus;
import com.example.backend.constants.LeaveSubmissionSource;
import com.example.backend.constants.LeaveType;

import java.time.LocalDateTime;

public record DriverLeaveHistoryResponse(
        Long id,
        Long leaveRequestId,
        Long driverId,
        LeaveRequestEventType eventType,
        LeaveSubmissionSource actorType,
        Long actorId,
        String actorAccount,
        LeaveRequestStatus oldStatus,
        LeaveRequestStatus newStatus,
        LeaveType oldLeaveType,
        LeaveType newLeaveType,
        String reason,
        LocalDateTime occurredAt
) {
}
