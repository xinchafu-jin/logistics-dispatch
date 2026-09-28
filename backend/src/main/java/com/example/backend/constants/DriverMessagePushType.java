package com.example.backend.constants;

/** WebSocket 推播的種類。管理員頻道和司機的私人頻道都會收到，前端看 type 決定怎麼處理。 */
public enum DriverMessagePushType {
    /** 新訊息：前端把 message 加進對話清單；message.exceptionCaseId 有值就是某件案件的對話 */
    MESSAGE,
    /** 已讀：前端把這位司機對話裡、readSenderType 發的訊息標成已讀（紅點歸零、顯示「已讀」）；exceptionCaseId 有值就只標那件案件 */
    READ,
    /** 司機建立了例外回報案件：後台鈴鐺加一、異常中心多一件；exceptionCase 是案件本體 */
    CASE_OPENED,
    /** 管理員在異常中心接收了案件：別的管理員鈴鐺消掉，司機端從「等待回覆」變「處理中」 */
    CASE_ACCEPTED,
    /** 管理員在異常中心結案：司機端輸入框換成處理結果，聊天室移除這件 */
    CASE_CLOSED
}
