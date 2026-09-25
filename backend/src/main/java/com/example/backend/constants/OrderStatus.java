package com.example.backend.constants;

public enum OrderStatus {
    PENDING_CONFIRM, // 待確認
    CONFIRMED,       // 已確認
    LOADED,          // 已點交：司機在倉庫點貨裝車完成，還沒抵達門市
    IN_DELIVERY,     // 配送中
    NO_SIGNATURE,    // 無人簽收
    COMPLETED,       // 已完成
    CANCELLED,       // 已取消
    FAILED;          // 配送失敗

    /**
     * 還沒結束的單：已確認、已點交、配送中。
     * 只看狀態、不管有沒有排進路線；呼叫端通常已經先用路線篩過訂單。
     */
    public boolean isActive() {
        return this == CONFIRMED || this == LOADED || this == IN_DELIVERY;
    }
}
