package com.example.backend.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Table(name = "admin_users")
@Entity
public class AdminUsersEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 60)
    private String account;

    /**
     * BCrypt 雜湊（60 字元）。長度放寬到 100 以容納帶前綴的編碼器
     * （DelegatingPasswordEncoder 的 {bcrypt}... 為 68 字元）或 Argon2。
     */
    @Column(nullable = false, length = 100)
    private String password;
    @Column(nullable = false, length = 60)
    private String name;

    /**
     * 允許 null 是為了相容資料庫內已存在、尚未補手機號碼的主管帳號。
     */
    @Column(length = 30)
    private String phone;
    // ai key 欄位
    @Column(name = "ai_api_key_encrypted", length = 512)
    private String aiApiKeyEncrypted;
    @Column(name = "ai_api_key_last4", length = 4)
    private String aiApiKeyLast4;
    @Column(name = "ai_api_key_updated_at")
    private LocalDateTime aiApiKeyUpdatedAt;

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

    public String getAiApiKeyEncrypted() {
        return aiApiKeyEncrypted;
    }

    public void setAiApiKeyEncrypted(String aiApiKeyEncrypted) {
        this.aiApiKeyEncrypted = aiApiKeyEncrypted;
    }

    public String getAiApiKeyLast4() {
        return aiApiKeyLast4;
    }

    public void setAiApiKeyLast4(String aiApiKeyLast4) {
        this.aiApiKeyLast4 = aiApiKeyLast4;
    }

    public LocalDateTime getAiApiKeyUpdatedAt() {
        return aiApiKeyUpdatedAt;
    }

    public void setAiApiKeyUpdatedAt(LocalDateTime aiApiKeyUpdatedAt) {
        this.aiApiKeyUpdatedAt = aiApiKeyUpdatedAt;
    }
}
