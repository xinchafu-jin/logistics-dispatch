package com.example.backend.constants;

/** 打卡相對於表定上班時間的判定。 */
public enum AttendancePunctualityStatus {
    ON_TIME,
    LATE_EXCUSED,
    LATE,
    LEAVE_REQUIRED,
    LEAVE_COVERED
}
