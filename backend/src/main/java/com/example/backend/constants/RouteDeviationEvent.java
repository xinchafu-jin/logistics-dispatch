package com.example.backend.constants;

/** 每收到一筆 GPS，偏離判斷的結果。 */
public enum RouteDeviationEvent {
    /** 沒有狀態變化：正常行駛、偏離次數還不夠、已經在偏離中、回來次數還不夠 */
    NONE,
    /** 這一筆讓司機從「正常」變成「偏離中」：要發警報，每次偏離只發這一次 */
    STARTED,
    /** 這一筆讓司機從「偏離中」回到「正常」：要發「已回到路線」 */
    RESOLVED
}
