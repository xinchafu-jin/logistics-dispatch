package com.example.backend.entity;

import jakarta.persistence.*;

import java.time.LocalTime;

@Entity
@Table(name = "drivers")
public class DriversEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所屬倉庫；舊資料可為 null，主管確認後設定，不從歷史路線推測。 */
    @Column(name = "warehouse_id")
    private Long warehouseId;

    public Long getWarehouseId() { return warehouseId; }
    public void setWarehouseId(Long warehouseId) { this.warehouseId = warehouseId; }

    /** 員工編號或手機號碼，登入帳號 */
    @Column(nullable = false, unique = true, length = 60)
    private String account;

    /**
     * BCrypt 雜湊（60 字元）。長度放寬到 100 以容納帶前綴的編碼器
     * （DelegatingPasswordEncoder 的 {bcrypt}... 為 68 字元）或 Argon2。
     * 允許 null 是為了相容已存在的舊司機資料。
     */
    @Column(length = 100)
    private String password;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(length = 30)
    private String phone;

    /** 司機大頭照的公開相對網址，例如 /uploads/driver-photos/{uuid}.jpg。 */
    @Column(name = "profile_photo_url", length = 500)
    private String profilePhotoUrl;

    @Column(nullable = false)
    private LocalTime workStart;

    @Column(nullable = false)
    private LocalTime workEnd;

    /** 休息時長（分鐘），由後台設定 */
    @Column(nullable = false)
    private Integer restDuration;

    /** 加班上限（分鐘）*/
    @Column
    private Integer maxOvertimeMinutes;

    @Column(nullable = false)
    private Boolean isActive = true;

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

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
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

    public String getProfilePhotoUrl() {
        return profilePhotoUrl;
    }

    public void setProfilePhotoUrl(String profilePhotoUrl) {
        this.profilePhotoUrl = profilePhotoUrl;
    }

    public LocalTime getWorkStart() {
        return workStart;
    }

    public void setWorkStart(LocalTime workStart) {
        this.workStart = workStart;
    }

    public LocalTime getWorkEnd() {
        return workEnd;
    }

    public void setWorkEnd(LocalTime workEnd) {
        this.workEnd = workEnd;
    }

    public Integer getRestDuration() {
        return restDuration;
    }

    public void setRestDuration(Integer restDuration) {
        this.restDuration = restDuration;
    }

    public Integer getMaxOvertimeMinutes() {
        return maxOvertimeMinutes;
    }

    public void setMaxOvertimeMinutes(Integer maxOvertimeMinutes) {
        this.maxOvertimeMinutes = maxOvertimeMinutes;
    }

    public Boolean getIsActive() {
        return isActive;
    }

    public void setIsActive(Boolean isActive) {
        this.isActive = isActive;
    }
}
