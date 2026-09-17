package com.example.backend.dto.respones;

import com.example.backend.constants.DriverApplicationStatus;

import java.time.LocalDateTime;

/** 申請人與主管共用的安全回應；永遠不包含身分證明碼或密碼雜湊。 */
public class DriverAccountApplicationResponse {

    private Long id;
    private String account;
    private String name;
    private String phone;
    private String nationalIdMasked;
    private DriverApplicationStatus status;
    private LocalDateTime appliedAt;
    private String reviewedBy;
    private LocalDateTime reviewedAt;
    private String rejectionReason;
    private Long approvedDriverId;

    public DriverAccountApplicationResponse() {
    }

    public DriverAccountApplicationResponse(
            Long id,
            String account,
            String name,
            String phone,
            String nationalIdMasked,
            DriverApplicationStatus status,
            LocalDateTime appliedAt,
            String reviewedBy,
            LocalDateTime reviewedAt,
            String rejectionReason,
            Long approvedDriverId
    ) {
        this.id = id;
        this.account = account;
        this.name = name;
        this.phone = phone;
        this.nationalIdMasked = nationalIdMasked;
        this.status = status;
        this.appliedAt = appliedAt;
        this.reviewedBy = reviewedBy;
        this.reviewedAt = reviewedAt;
        this.rejectionReason = rejectionReason;
        this.approvedDriverId = approvedDriverId;
    }

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

    public String getNationalIdMasked() {
        return nationalIdMasked;
    }

    public void setNationalIdMasked(String nationalIdMasked) {
        this.nationalIdMasked = nationalIdMasked;
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
