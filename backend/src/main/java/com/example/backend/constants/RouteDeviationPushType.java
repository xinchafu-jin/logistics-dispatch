package com.example.backend.constants;

/** 偏離推播的種類：後台收到後決定要跳提示、升級成警報，還是從清單拿掉。 */
public enum RouteDeviationPushType {
    /** 新的一筆偏離（提示） */
    STARTED,
    /** 偏離 10 分鐘還沒結束，升級成警報 */
    ESCALATED,
    /** 偏離結束（回到路線、交貨、休息、收車、下班） */
    ENDED
}
