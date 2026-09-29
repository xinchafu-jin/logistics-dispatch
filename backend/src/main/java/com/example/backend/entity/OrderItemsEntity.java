package com.example.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/** 訂單內可逐項點交的實際商品明細。 */
@Entity
@Table(name = "order_items")
public class OrderItemsEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private OrdersEntity order;

    @Column(length = 50)
    private String productCode;

    @Column(nullable = false, length = 100)
    private String itemName;

    @Column(nullable = false)
    private Integer expectedQuantity;

    @Column(nullable = false, length = 20)
    private String unit = "件";

    @Column(nullable = false)
    private Integer sequence;

    @Column(length = 255)
    private String notes;

    /** 司機實際清點的數量；尚未點交時為 null。 */
    @Column
    private Integer loadedQuantity;

    @Column
    private LocalDateTime checkedAt;

    @Column
    private Long checkedByDriverId;

    @Column(length = 255)
    private String loadingNotes;

    @Column(nullable = false)
    private boolean loadingMismatchReported;

    public boolean isLoadingMismatchReported() {
        return loadingMismatchReported;
    }

    public void setLoadingMismatchReported(boolean loadingMismatchReported) {
        this.loadingMismatchReported = loadingMismatchReported;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public OrdersEntity getOrder() {
        return order;
    }

    public void setOrder(OrdersEntity order) {
        this.order = order;
    }

    public String getProductCode() {
        return productCode;
    }

    public void setProductCode(String productCode) {
        this.productCode = productCode;
    }

    public String getItemName() {
        return itemName;
    }

    public void setItemName(String itemName) {
        this.itemName = itemName;
    }

    public Integer getExpectedQuantity() {
        return expectedQuantity;
    }

    public void setExpectedQuantity(Integer expectedQuantity) {
        this.expectedQuantity = expectedQuantity;
    }

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }

    public Integer getSequence() {
        return sequence;
    }

    public void setSequence(Integer sequence) {
        this.sequence = sequence;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public Integer getLoadedQuantity() {
        return loadedQuantity;
    }

    public void setLoadedQuantity(Integer loadedQuantity) {
        this.loadedQuantity = loadedQuantity;
    }

    public LocalDateTime getCheckedAt() {
        return checkedAt;
    }

    public void setCheckedAt(LocalDateTime checkedAt) {
        this.checkedAt = checkedAt;
    }

    public Long getCheckedByDriverId() {
        return checkedByDriverId;
    }

    public void setCheckedByDriverId(Long checkedByDriverId) {
        this.checkedByDriverId = checkedByDriverId;
    }

    public String getLoadingNotes() {
        return loadingNotes;
    }

    public void setLoadingNotes(String loadingNotes) {
        this.loadingNotes = loadingNotes;
    }
}
