package com.example.backend.constants;

/** 前端用來直接區分預排、臨請、事後補請與系統未到，不必用日期或 batchId 猜測。 */
public enum LeaveRequestMode {
    PREPLANNED,
    TEMPORARY,
    MAKEUP,
    SYSTEM_NO_SHOW,
    ADMIN_PLANNED_PARTIAL
}
