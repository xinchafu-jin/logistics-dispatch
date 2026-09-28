package com.example.backend.dto.respones;

import com.example.backend.constants.DriverMessagePushType;
import com.example.backend.constants.MessageSender;

import java.time.LocalDateTime;

/**
 * 透過 WebSocket 推給前端的內容。訊息、已讀的推播同時也是 Service 發出的事件本身；
 * 案件建立、接收、結案則包在 DriverCasePushEvent 裡發（後台和司機各一份）。
 *
 * <p>用 ofMessage／ofRead／ofCaseRead／ofCase 建立，不要自己 new 再逐一 set，免得 type 跟欄位對不上
 * （例如 type 是 READ 卻沒填 readSenderType）。</p>
 */
public class DriverMessagePushResponse {

    private DriverMessagePushType type;
    /** 哪位司機的對話串；每種 type 都有 */
    private Long driverId;
    /** 新訊息本體；只有 MESSAGE 有 */
    private DriverMessageResponse message;
    /** 被讀的是哪一方發的訊息；只有 READ 有。DRIVER＝管理員讀了司機的訊息，ADMIN＝司機讀了調度中心的回覆 */
    private MessageSender readSenderType;
    /** 標已讀的時間；只有 READ 有，前端直接拿來填 readAt，不用再查一次 */
    private LocalDateTime readAt;
    /** 哪件案件：READ 有值＝只標那件案件的對話，null＝一般對話；CASE_* 一定有值。MESSAGE 改看 message.exceptionCaseId */
    private Long exceptionCaseId;
    /** 案件本體；只有 CASE_* 有。給司機的是 DriverCaseResponse，給後台的是 AdminDriverCaseResponse */
    private DriverCaseResponse exceptionCase;

    public static DriverMessagePushResponse ofMessage(DriverMessageResponse message) {
        DriverMessagePushResponse push = new DriverMessagePushResponse();
        push.setType(DriverMessagePushType.MESSAGE);
        push.setDriverId(message.getDriverId());
        push.setMessage(message);
        return push;
    }

    public static DriverMessagePushResponse ofRead(Long driverId, MessageSender readSenderType, LocalDateTime readAt) {
        DriverMessagePushResponse push = new DriverMessagePushResponse();
        push.setType(DriverMessagePushType.READ);
        push.setDriverId(driverId);
        push.setReadSenderType(readSenderType);
        push.setReadAt(readAt);
        return push;
    }

    /** 某件案件的對話標了已讀；前端只改那一串，一般對話的已讀不受影響 */
    public static DriverMessagePushResponse ofCaseRead(
            Long driverId, Long exceptionCaseId, MessageSender readSenderType, LocalDateTime readAt) {
        DriverMessagePushResponse push = ofRead(driverId, readSenderType, readAt);
        push.setExceptionCaseId(exceptionCaseId);
        return push;
    }

    /** 案件建立、接收、結案；type 只能是 CASE_* */
    public static DriverMessagePushResponse ofCase(
            DriverMessagePushType type, Long driverId, DriverCaseResponse exceptionCase) {
        DriverMessagePushResponse push = new DriverMessagePushResponse();
        push.setType(type);
        push.setDriverId(driverId);
        push.setExceptionCaseId(exceptionCase.getId());
        push.setExceptionCase(exceptionCase);
        return push;
    }

    public DriverMessagePushType getType() {
        return type;
    }

    public void setType(DriverMessagePushType type) {
        this.type = type;
    }

    public Long getDriverId() {
        return driverId;
    }

    public void setDriverId(Long driverId) {
        this.driverId = driverId;
    }

    public DriverMessageResponse getMessage() {
        return message;
    }

    public void setMessage(DriverMessageResponse message) {
        this.message = message;
    }

    public MessageSender getReadSenderType() {
        return readSenderType;
    }

    public void setReadSenderType(MessageSender readSenderType) {
        this.readSenderType = readSenderType;
    }

    public LocalDateTime getReadAt() {
        return readAt;
    }

    public void setReadAt(LocalDateTime readAt) {
        this.readAt = readAt;
    }

    public Long getExceptionCaseId() {
        return exceptionCaseId;
    }

    public void setExceptionCaseId(Long exceptionCaseId) {
        this.exceptionCaseId = exceptionCaseId;
    }

    public DriverCaseResponse getExceptionCase() {
        return exceptionCase;
    }

    public void setExceptionCase(DriverCaseResponse exceptionCase) {
        this.exceptionCase = exceptionCase;
    }
}
