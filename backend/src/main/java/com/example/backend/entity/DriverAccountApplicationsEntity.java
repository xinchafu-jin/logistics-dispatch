package com.example.backend.entity;

import com.example.backend.constants.DriverApplicationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/** 司機本人送出的帳號申請；核准前不會建立正式 drivers 資料。 */
@Entity
@Table(name = "driver_account_applications")
public class DriverAccountApplicationsEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 60)
    private String account;

    /** 身分證字號送件時立即 BCrypt，資料庫不保存明碼。 */
    @Column(nullable = false, length = 100)
    private String passwordHash;

    /** 只供主管辨識申請人，不足以還原身分證字號。 */
    @Column(nullable = false, length = 10)
    private String nationalIdMasked;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(nullable = false, length = 30)
    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DriverApplicationStatus status = DriverApplicationStatus.PENDING;

    @Column(nullable = false)
    private LocalDateTime appliedAt;

    @Column(length = 50)
    private String reviewedBy;

    @Column
    private LocalDateTime reviewedAt;

    @Column(length = 500)
    private String rejectionReason;

    /** 核准後所建立的正式司機 ID。 */
    @Column
    private Long approvedDriverId;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getAccount() {
        return account;
    }

    public void setAccount(String account) {
        this.account = account;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getNationalIdMasked() {
        return nationalIdMasked;
    }

    public void setNationalIdMasked(String nationalIdMasked) {
        this.nationalIdMasked = nationalIdMasked;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public DriverApplicationStatus getStatus() {
        return status;
    }

    public void setStatus(DriverApplicationStatus status) {
        this.status = status;
    }

    public LocalDateTime getAppliedAt() {
        return appliedAt;
    }

    public void setAppliedAt(LocalDateTime appliedAt) {
        this.appliedAt = appliedAt;
    }

    public String getReviewedBy() {
        return reviewedBy;
    }

    public void setReviewedBy(String reviewedBy) {
        this.reviewedBy = reviewedBy;
    }

    public LocalDateTime getReviewedAt() {
        return reviewedAt;
    }

    public void setReviewedAt(LocalDateTime reviewedAt) {
        this.reviewedAt = reviewedAt;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public void setRejectionReason(String rejectionReason) {
        this.rejectionReason = rejectionReason;
    }

    public Long getApprovedDriverId() {
        return approvedDriverId;
    }

    public void setApprovedDriverId(Long approvedDriverId) {
        this.approvedDriverId = approvedDriverId;
    }
}
