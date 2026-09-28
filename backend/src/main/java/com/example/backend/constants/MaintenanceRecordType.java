package com.example.backend.constants;

/** 一筆送修紀錄是哪一種（vehicle_maintenance_records.type）。 */
public enum MaintenanceRecordType {
    /** 小保：完成時重設小保基準 */
    MINOR,
    /** 大保：完成時重設大保基準 */
    MAJOR,
    /** 維修（車禍、故障）：只記次數，不重設任何基準 */
    REPAIR
}
