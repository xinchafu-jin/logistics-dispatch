package com.example.backend.constants;

public enum VehicleStatus {
    AVAILABLE,
    /** 送維修（車禍、故障）；不算小保、大保，完成後不重設保養基準 */
    MAINTENANCE,
    RETIRED,
    /** 送小保；完成（改回 AVAILABLE）時把當下的行車紀錄器里程記成新的小保基準 */
    MINOR_MAINTENANCE,
    /** 送大保；完成時記成新的大保基準，不重設小保 */
    MAJOR_MAINTENANCE,
}
