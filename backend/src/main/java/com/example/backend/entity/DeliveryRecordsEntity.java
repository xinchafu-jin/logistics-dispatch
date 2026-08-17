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

    /** 交貨地點 GPS */
    @Column
    private Double lat;

    @Column
    private Double lng;

    @Column
    private Integer deliveredBoxCount;

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
