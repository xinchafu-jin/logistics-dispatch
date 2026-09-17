package com.example.backend.constants;

public enum OrderStatus {
    PENDING_CONFIRM, // 待確認
    CONFIRMED,       // 已確認
    IN_DELIVERY,     // 配送中
    NO_SIGNATURE,    // 無人簽收
    COMPLETED,       // 已完成
    CANCELLED,       // 已取消
    FAILED           // 配送失敗
}
