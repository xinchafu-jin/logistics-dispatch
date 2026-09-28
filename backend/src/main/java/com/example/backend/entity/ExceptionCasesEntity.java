package com.example.backend.entity;

import com.example.backend.constants.DriverCaseCategory;
import com.example.backend.constants.ExceptionStatus;
import com.example.backend.constants.ExceptionType;
import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 異常案件。來源包含司機無人簽收、司機例外回報，以及電話處理後的補登。
 *
 * <p>司機例外回報（type = DRIVER_REPORT）另外用到 driverId 以下七個欄位（見 V14）；
 * 其他類型的異常，這幾欄都是 null。</p>
 */
@Entity
@Table(name = "exception_cases")
public class ExceptionCasesEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 關聯訂單，可為空（例如與特定訂單無關的現場狀況）*/
    @Column
    private Long orderId;

    /** 發生異常的配送紀錄。 */
    @Column
    private Long deliveryRecordId;

    /** 因這次異常建立的後續訂單。 */
    @Column
    private Long followUpOrderId;

    /** 隔日主管最早可處理的時間。 */
    @Column
    private LocalDateTime reviewAvailableAt;

    /** 異常進入待確認佇列的時間。 */
    @Column
    private LocalDateTime queuedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ExceptionType type;

    @Column(length = 1000)
    private String description;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(length = 50)
    private String handledBy;

    @Column
    private LocalDateTime handledAt;

    @Column(length = 1000)
    private String resolution;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ExceptionStatus status = ExceptionStatus.OPEN;

    /** 回報的司機；只有司機回報有值 */
    @Column
    private Long driverId;

    /** 回報當下這位司機今天已發布的路線；今天沒排路線是 null */
    @Column
    private Long routeId;

    /** 司機回報的分類。資料表是 VARCHAR(30)，存 enum 名稱 */
    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private DriverCaseCategory category;

    /** 司機說還能不能繼續配送；後台清單把不能繼續的排前面 */
    @Column
    private Boolean canContinue;

    /** 司機附的照片，只收交貨照片上傳 API 回傳的網址 */
    @Column(length = 500)
    private String photoUrl;

    /** 在異常中心按「接收」的管理員；null＝還沒有人接收 */
    @Column
    private Long acceptedAdminId;

    @Column
    private LocalDateTime acceptedAt;

    @PrePersist
    public void onCreate() {
        // 指定台北時間：正式機的 JVM 是 UTC，不指定的話建立時間會比 Service 寫的處理、接收時間慢 8 小時
        this.createdAt = LocalDateTime.now(ZoneId.of("Asia/Taipei"));
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public Long getDeliveryRecordId() {
        return deliveryRecordId;
    }

    public void setDeliveryRecordId(Long deliveryRecordId) {
        this.deliveryRecordId = deliveryRecordId;
    }

    public Long getFollowUpOrderId() {
        return followUpOrderId;
    }

    public void setFollowUpOrderId(Long followUpOrderId) {
        this.followUpOrderId = followUpOrderId;
    }

    public LocalDateTime getReviewAvailableAt() {
        return reviewAvailableAt;
    }

    public void setReviewAvailableAt(LocalDateTime reviewAvailableAt) {
        this.reviewAvailableAt = reviewAvailableAt;
    }

    public LocalDateTime getQueuedAt() {
        return queuedAt;
    }

    public void setQueuedAt(LocalDateTime queuedAt) {
        this.queuedAt = queuedAt;
    }

    public ExceptionType getType() {
        return type;
    }

    public void setType(ExceptionType type) {
        this.type = type;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public String getHandledBy() {
        return handledBy;
    }

    public void setHandledBy(String handledBy) {
        this.handledBy = handledBy;
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

    public ExceptionStatus getStatus() {
        return status;
    }

    public void setStatus(ExceptionStatus status) {
        this.status = status;
    }

    public Long getDriverId() {
        return driverId;
    }

    public void setDriverId(Long driverId) {
        this.driverId = driverId;
    }

    public Long getRouteId() {
        return routeId;
    }

    public void setRouteId(Long routeId) {
        this.routeId = routeId;
    }

    public DriverCaseCategory getCategory() {
        return category;
    }

    public void setCategory(DriverCaseCategory category) {
        this.category = category;
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

    public Long getAcceptedAdminId() {
        return acceptedAdminId;
    }

    public void setAcceptedAdminId(Long acceptedAdminId) {
        this.acceptedAdminId = acceptedAdminId;
    }

    public LocalDateTime getAcceptedAt() {
        return acceptedAt;
    }

    public void setAcceptedAt(LocalDateTime acceptedAt) {
        this.acceptedAt = acceptedAt;
    }
}
