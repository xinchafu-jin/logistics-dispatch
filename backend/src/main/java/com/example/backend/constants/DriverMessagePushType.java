package com.example.backend.constants;

/** WebSocket 推播的種類。管理員頻道和司機的私人頻道都會收到這兩種，前端看 type 決定怎麼處理。 */
public enum DriverMessagePushType {
    /** 新訊息：前端把 message 加進對話清單 */
    MESSAGE,
    /** 已讀：前端把這位司機對話裡、readSenderType 發的訊息標成已讀（紅點歸零、顯示「已讀」） */
    READ
}
