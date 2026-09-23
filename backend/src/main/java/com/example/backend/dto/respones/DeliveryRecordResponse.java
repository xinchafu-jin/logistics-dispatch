package com.example.backend.dto.respones;

import com.example.backend.constants.OrderStatus;

import java.time.LocalDateTime;
import java.time.LocalDate;

/** 抵達、交貨及無人簽收共用的交貨紀錄回應。 */
public class DeliveryRecordResponse {

    private Long id;
    private Long orderId;
    private OrderStatus orderStatus;
    private LocalDateTime arrivedAt;
    private LocalDateTime deliveredAt;
    private Integer expectedBoxCount;
    private Integer deliveredBoxCount;
    private Integer shortageBoxCount;
    private Integer damagedBoxCount;
    private Integer replacementRequiredBoxCount;
    private String photoUrl;
    private String notes;
    private Boolean noSignature;
    private Long exceptionCaseId;
    private Long followUpOrderId;
    private String followUpOrderNumber;
    private LocalDate followUpDeliveryDate;

    public DeliveryRecordResponse() {
    }

    public DeliveryRecordResponse(
            Long id,
            Long orderId,
            OrderStatus orderStatus,
            LocalDateTime arrivedAt,
            LocalDateTime deliveredAt,
            Integer expectedBoxCount,
            Integer deliveredBoxCount,
            Integer shortageBoxCount,
            Integer damagedBoxCount,
            Integer replacementRequiredBoxCount,
            String photoUrl,
            String notes,
            Boolean noSignature,
            Long exceptionCaseId,
            Long followUpOrderId,
            String followUpOrderNumber,
            LocalDate followUpDeliveryDate
    ) {
        this.id = id;
        this.orderId = orderId;
        this.orderStatus = orderStatus;
        this.arrivedAt = arrivedAt;
        this.deliveredAt = deliveredAt;
        this.expectedBoxCount = expectedBoxCount;
        this.deliveredBoxCount = deliveredBoxCount;
        this.shortageBoxCount = shortageBoxCount;
        this.damagedBoxCount = damagedBoxCount;
        this.replacementRequiredBoxCount = replacementRequiredBoxCount;
        this.photoUrl = photoUrl;
        this.notes = notes;
        this.noSignature = noSignature;
        this.exceptionCaseId = exceptionCaseId;
        this.followUpOrderId = followUpOrderId;
        this.followUpOrderNumber = followUpOrderNumber;
        this.followUpDeliveryDate = followUpDeliveryDate;
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

    public OrderStatus getOrderStatus() {
        return orderStatus;
    }

    public void setOrderStatus(OrderStatus orderStatus) {
        this.orderStatus = orderStatus;
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

    public Integer getExpectedBoxCount() {
        return expectedBoxCount;
    }

    public void setExpectedBoxCount(Integer expectedBoxCount) {
        this.expectedBoxCount = expectedBoxCount;
    }

    public Integer getDeliveredBoxCount() {
        return deliveredBoxCount;
    }

    public void setDeliveredBoxCount(Integer deliveredBoxCount) {
        this.deliveredBoxCount = deliveredBoxCount;
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

    public Long getExceptionCaseId() {
        return exceptionCaseId;
    }

    public void setExceptionCaseId(Long exceptionCaseId) {
        this.exceptionCaseId = exceptionCaseId;
    }

    public Long getFollowUpOrderId() {
        return followUpOrderId;
    }

    public void setFollowUpOrderId(Long followUpOrderId) {
        this.followUpOrderId = followUpOrderId;
    }

    public String getFollowUpOrderNumber() {
        return followUpOrderNumber;
    }

    public void setFollowUpOrderNumber(String followUpOrderNumber) {
        this.followUpOrderNumber = followUpOrderNumber;
    }

    public LocalDate getFollowUpDeliveryDate() {
        return followUpDeliveryDate;
    }

    public void setFollowUpDeliveryDate(LocalDate followUpDeliveryDate) {
        this.followUpDeliveryDate = followUpDeliveryDate;
    }
}
