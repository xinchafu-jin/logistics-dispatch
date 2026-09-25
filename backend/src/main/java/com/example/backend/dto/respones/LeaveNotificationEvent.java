package com.example.backend.dto.respones;

/** 交易成功後才送出的 WebSocket 請假通知。 */
public record LeaveNotificationEvent(boolean notifyAdmins, Long driverId, DriverLeaveResponse payload) {
    public static LeaveNotificationEvent admins(DriverLeaveResponse payload) {
        return new LeaveNotificationEvent(true, payload.driverId(), payload);
    }

    public static LeaveNotificationEvent driver(DriverLeaveResponse payload) {
        return new LeaveNotificationEvent(false, payload.driverId(), payload);
    }
}
