package com.example.backend.constants;

/** 司機請假與出勤異常種類；ABSENT（曠職）只能由主管認定。 */
public enum LeaveType {
    SICK,
    PERSONAL,
    ANNUAL,
    SPECIAL,
    BEREAVEMENT,
    MENSTRUAL,
    ABSENT
}
