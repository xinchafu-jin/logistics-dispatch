package com.example.backend.constants;

/** 一筆偏離紀錄為什麼結束（route_deviations.end_reason）。 */
public enum RouteDeviationEndReason {
    /** 偏離中連續 2 筆回到 100 公尺內 */
    BACK_ON_ROUTE,
    /** 到門市開始交貨（有訂單變成 IN_DELIVERY）；結束時間約等於到店時間 */
    DELIVERING,
    /** 開始休息；休息中不發警報 */
    ON_BREAK,
    /** 收車（mileage_logs.end_time 有值） */
    TRIP_ENDED,
    /** 下班或沒有出勤紀錄 */
    OFF_DUTY
}
