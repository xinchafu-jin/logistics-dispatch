package com.example.backend.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 交貨紀錄。一張訂單可能有多筆（無人簽收後隔日重送），故獨立成表。
 */
@Entity
@Table(name = "delivery_records")
public class DeliveryRecordsEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long orderId;

    @Column
    private LocalDateTime arrivedAt;

    @Column
    private LocalDateTime deliveredAt;

    /** 這一站完成處理的時間；正常交貨與無人簽收都會記錄。 */
    @Column
    private LocalDateTime handledAt;

    /** 交貨地點 GPS */
    @Column
    private Double lat;

    @Column
    private Double lng;

    @Column
    private Integer deliveredBoxCount;

    /** 本次配送開始時訂單記載的應到箱數快照。 */
    @Column
    private Integer expectedBoxCount;

    @Column
    private Integer shortageBoxCount;

    @Column
    private Integer damagedBoxCount;

    @Column
    private Integer replacementRequiredBoxCount;

    @Column(length = 500)
    private String photoUrl;

    @Column(length = 500)
    private String notes;

    /** 是否為無人簽收 */
    @Column(nullable = false)
    private Boolean noSignature = false;

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

    public LocalDateTime getArrivedAt() {
        return arrivedAt;
    }

    public void setArrivedAt(LocalDateTime arrivedAt) {
        this.arrivedAt = arrivedAt;
    }

    public LocalDateTime getDeliveredAt() {
        return deliveredAt;
    }

    public void setDeliveredAt(LocalDateTime deliveredAt) {
        this.deliveredAt = deliveredAt;
    }

    public LocalDateTime getHandledAt() {
        return handledAt;
    }

    public void setHandledAt(LocalDateTime handledAt) {
        this.handledAt = handledAt;
    }

    public Double getLat() {
        return lat;
    }

    public void setLat(Double lat) {
        this.lat = lat;
    }

    public Double getLng() {
        return lng;
    }

    public void setLng(Double lng) {
        this.lng = lng;
    }

    public Integer getDeliveredBoxCount() {
        return deliveredBoxCount;
    }

    public void setDeliveredBoxCount(Integer deliveredBoxCount) {
        this.deliveredBoxCount = deliveredBoxCount;
    }

    public Integer getExpectedBoxCount() {
        return expectedBoxCount;
    }

    public void setExpectedBoxCount(Integer expectedBoxCount) {
        this.expectedBoxCount = expectedBoxCount;
    }

    public Integer getShortageBoxCount() {
        return shortageBoxCount;
    }

    public void setShortageBoxCount(Integer shortageBoxCount) {
        this.shortageBoxCount = shortageBoxCount;
    }

    public Integer getDamagedBoxCount() {
        return damagedBoxCount;
    }

    public void setDamagedBoxCount(Integer damagedBoxCount) {
        this.damagedBoxCount = damagedBoxCount;
    }

    public Integer getReplacementRequiredBoxCount() {
        return replacementRequiredBoxCount;
    }

    public void setReplacementRequiredBoxCount(Integer replacementRequiredBoxCount) {
        this.replacementRequiredBoxCount = replacementRequiredBoxCount;
    }

    public String getPhotoUrl() {
        return photoUrl;
    }

    public void setPhotoUrl(String photoUrl) {
        this.photoUrl = photoUrl;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public Boolean getNoSignature() {
        return noSignature;
    }

    public void setNoSignature(Boolean noSignature) {
        this.noSignature = noSignature;
    }
}
