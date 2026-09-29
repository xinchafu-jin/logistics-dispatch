package com.example.backend.dto.respones;

import com.example.backend.constants.MessageSender;

import java.time.LocalDateTime;

/**
 * 一則聊天訊息。司機端與後台共用。
 * 不帶 senderAdminId：司機端只需要知道是「調度中心」回的，是哪位管理員留在資料庫給後台追查。
 */
public class DriverMessageResponse {

    /** 前端拿最後一則的 id 當下一次查詢的 afterId，也用它去重 */
    private Long id;
    private Long driverId;
    private MessageSender senderType;
    private String content;
    private LocalDateTime createdAt;
    /** null 代表對方還沒讀 */
    private LocalDateTime readAt;
    /** 屬於哪件案件的對話；null＝一般對話。前端靠它把推播分到對的那一串 */
    private Long exceptionCaseId;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getDriverId() {
        return driverId;
    }

    public void setDriverId(Long driverId) {
        this.driverId = driverId;
    }

    public MessageSender getSenderType() {
        return senderType;
    }

    public void setSenderType(MessageSender senderType) {
        this.senderType = senderType;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
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
}
