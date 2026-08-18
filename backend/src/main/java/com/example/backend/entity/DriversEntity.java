package com.example.backend.entity;

import jakarta.persistence.*;

import java.time.LocalTime;

@Entity
@Table(name = "drivers")
public class DriversEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 員工編號或手機號碼，登入帳號 */
    @Column(nullable = false, unique = true, length = 50)
    private String account;

    /** BCrypt 雜湊。允許 null 是為了相容已存在的舊司機資料。 */
    @Column(length = 60)
    private String password;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(length = 30)
    private String phone;

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
