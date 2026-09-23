package com.example.backend.constants;

/**
 * 聊天訊息的發送方。名稱要跟 driver_messages.sender_type 的 enum 值一字不差，
 * EnumType.STRING 存進資料庫的就是這裡的名稱。
 */
public enum MessageSender {
    /** 司機 */
    DRIVER,
    /** 調度中心（任一位管理員） */
    ADMIN
}
