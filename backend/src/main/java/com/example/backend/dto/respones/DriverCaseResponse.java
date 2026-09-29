package com.example.backend.dto.respones;

import com.example.backend.constants.DriverCaseCategory;
import com.example.backend.constants.ExceptionStatus;

import java.time.LocalDateTime;

/**
 * 司機端看到的一件例外回報案件：GET /api/driver/cases、建立後的回應、推播裡的 exceptionCase 都是這個形狀。
 * 欄位對照 frontend-driver 的 DriverCaseDto。
 *
 * <p>刻意不帶是哪位管理員接收、結案：司機端只需要知道是「調度中心」，跟聊天訊息不帶 senderAdminId 同一個理由。
 * 後台要的欄位放在子類別 AdminDriverCaseResponse，不要把子類別的物件回給司機。</p>
 */
public class DriverCaseResponse {

    private Long id;
    /** 舊版 API 建的回報沒有分類，會是 null */
    private DriverCaseCategory category;
    private ExceptionStatus status;
    /** 跟某張單有關才有；整台車的狀況（車輛、路況）是 null */
    private Long orderId;
    private String orderNumber;
    private String storeName;
    private String description;
    private Boolean canContinue;
    private String photoUrl;
    private LocalDateTime createdAt;
    /** 接收時間；null＝還沒有人接收，司機端顯示「等待回覆」 */
    private LocalDateTime acceptedAt;
    /** 結案時間；OPEN 時是 null */
    private LocalDateTime handledAt;
    /** 結案時填的處理結果；OPEN 時是 null */
    private String resolution;
    /**
     * 對方發的、還沒讀的訊息數：司機端是調度中心的回覆，後台是司機的訊息。
     * 推播裡固定是 0，收到的一方保留自己算的未讀數（未讀由 MESSAGE、READ 推播在算）。
     */
    private long unreadCount;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public DriverCaseCategory getCategory() {
        return category;
    }

    public void setCategory(DriverCaseCategory category) {
        this.category = category;
    }

    public ExceptionStatus getStatus() {
        return status;
    }

    public void setStatus(ExceptionStatus status) {
        this.status = status;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public String getOrderNumber() {
        return orderNumber;
    }

    public void setOrderNumber(String orderNumber) {
        this.orderNumber = orderNumber;
    }

    public String getStoreName() {
        return storeName;
    }

    public void setStoreName(String storeName) {
        this.storeName = storeName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Boolean getCanContinue() {
        return canContinue;
    }

    public void setCanContinue(Boolean canContinue) {
        this.canContinue = canContinue;
    }

    public String getPhotoUrl() {
        return photoUrl;
    }

    public void setPhotoUrl(String photoUrl) {
        this.photoUrl = photoUrl;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getAcceptedAt() {
        return acceptedAt;
    }

    public void setAcceptedAt(LocalDateTime acceptedAt) {
        this.acceptedAt = acceptedAt;
    }

    public LocalDateTime getHandledAt() {
        return handledAt;
    }

    public void setHandledAt(LocalDateTime handledAt) {
        this.handledAt = handledAt;
    }

    public String getResolution() {
        return resolution;
    }

    public void setResolution(String resolution) {
        this.resolution = resolution;
    }

    public long getUnreadCount() {
        return unreadCount;
    }

    public void setUnreadCount(long unreadCount) {
        this.unreadCount = unreadCount;
    }
}
