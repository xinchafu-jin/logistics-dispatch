package com.example.backend.entity;

import com.example.backend.constants.MessageSender;
import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 司機與調度中心之間的聊天訊息。
 *
 * <p>一位司機有一串一般對話（exceptionCaseId 是 null），加上每件例外回報案件各一串。
 * 一般對話的查詢一律要加 exceptionCaseId IS NULL，不然案件訊息會混進一般對話。</p>
 */
@Entity
@Table(name = "driver_messages")
public class DriverMessagesEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 屬於哪位司機的對話串，不是寄件人；管理員回覆時也是填這位司機 */
    @Column(nullable = false)
    private Long driverId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MessageSender senderType;

    /** 回覆的管理員；司機發的訊息一律為 null */
    @Column
    private Long senderAdminId;

    @Column(nullable = false, length = 1000)
    private String content;

    /** 由 Service 用台北時間寫入，建立後不再更新 */
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 對方讀到的時間，null 代表未讀。司機發的由任一位管理員讀，管理員發的由司機讀 */
    @Column
    private LocalDateTime readAt;

    /** 屬於哪件例外回報案件的對話；null＝一般對話 */
    @Column
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

    public Long getSenderAdminId() {
        return senderAdminId;
    }

    public void setSenderAdminId(Long senderAdminId) {
        this.senderAdminId = senderAdminId;
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
