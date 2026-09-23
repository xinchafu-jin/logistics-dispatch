package com.example.backend.dto.respones;

import com.example.backend.constants.AttendanceStatus;

public class EmergencyLeaveReplacementCandidateResponse {
    private final Long driverId;
    private final String account;
    private final String name;
    private final AttendanceStatus attendanceStatus;
    private final boolean clockedIn;

    public EmergencyLeaveReplacementCandidateResponse(
            Long driverId, String account, String name,
            AttendanceStatus attendanceStatus, boolean clockedIn
    ) {
        this.driverId = driverId;
        this.account = account;
        this.name = name;
        this.attendanceStatus = attendanceStatus;
        this.clockedIn = clockedIn;
    }

    public Long getDriverId() { return driverId; }
    public String getAccount() { return account; }
    public String getName() { return name; }
    public AttendanceStatus getAttendanceStatus() { return attendanceStatus; }
    public boolean isClockedIn() { return clockedIn; }
}
