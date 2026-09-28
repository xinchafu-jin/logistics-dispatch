package com.example.backend.constants;

/** 一筆送修紀錄的狀態（vehicle_maintenance_records.status）。 */
public enum MaintenanceRecordStatus {
    /** 進行中：車輛在保養或維修，不能派車 */
    ACTIVE,
    /** 完成：計入次數，小保、大保會更新基準 */
    COMPLETED,
    /** 取消：不計次數、不更新基準，只留紀錄 */
    CANCELLED
}
