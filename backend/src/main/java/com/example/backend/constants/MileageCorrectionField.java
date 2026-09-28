package com.example.backend.constants;

/** 主管更正的是哪一個數字（vehicle_mileage_corrections.field）。 */
public enum MileageCorrectionField {
    /** 目前的行車紀錄器里程；車在外面跑時，也就是這趟的出車讀數 */
    CURRENT_ODOMETER,
    /** 上次小保時的行車紀錄器里程 */
    MINOR_BASELINE,
    /** 上次大保時的行車紀錄器里程 */
    MAJOR_BASELINE
}
