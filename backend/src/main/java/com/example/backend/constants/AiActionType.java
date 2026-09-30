package com.example.backend.constants;

public enum AiActionType {
    ASSIGN_DRIVER, // 指派司機給某條路線
    MOVE_ORDER,     // 將訂單移到另一台車
    PUBLISH_DAY,
    UNASSIGN_DRIVER,
    WITHDRAW_DAY    // 撤回某一天全部倉庫的發布

}
