package com.example.backend.dto.respones;

import java.time.Instant;

public class LoginResponse {

    private String accessToken;
    private String tokenType;
    private Instant expiresAt;
    private String role;
    private Long userId;
    private String account;
    private String name;

    public LoginResponse() {
    }

    public LoginResponse(
            String accessToken,
            String tokenType,
            Instant expiresAt,
            String role,
            Long userId,
            String account,
            String name
    ) {
        this.accessToken = accessToken;
        this.tokenType = tokenType;
        this.expiresAt = expiresAt;
        this.role = role;
        this.userId = userId;
        this.account = account;
        this.name = name;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public String getTokenType() {
        return tokenType;
    }

    public void setTokenType(String tokenType) {
        this.tokenType = tokenType;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
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
}
