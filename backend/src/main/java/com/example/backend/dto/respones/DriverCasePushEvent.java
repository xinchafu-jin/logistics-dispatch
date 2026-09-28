package com.example.backend.dto.respones;

/**
 * 案件建立、接收、結案時 DriverCaseService 發出的事件，DriverMessagesPushService 在交易 commit 後才推。
 *
 * <p>為什麼要兩份：後台要知道是誰回報、誰接收（AdminDriverCaseResponse），司機端只該知道是「調度中心」
 * （DriverCaseResponse）。訊息、已讀的推播兩邊內容一樣，所以直接發 DriverMessagePushResponse 就好；
 * 案件的推播兩邊不一樣，才另外包成這個事件，各送各的頻道。</p>
 */
public class DriverCasePushEvent {

    /** 送到所有管理員共用的頻道 */
    private final DriverMessagePushResponse adminPush;
    /** 送到回報司機的私人頻道；舊版 API 建的回報沒有司機，會是 null */
    private final DriverMessagePushResponse driverPush;

    public DriverCasePushEvent(DriverMessagePushResponse adminPush, DriverMessagePushResponse driverPush) {
        this.adminPush = adminPush;
        this.driverPush = driverPush;
    }

    public DriverMessagePushResponse getAdminPush() {
        return adminPush;
    }

    public DriverMessagePushResponse getDriverPush() {
        return driverPush;
    }
}
