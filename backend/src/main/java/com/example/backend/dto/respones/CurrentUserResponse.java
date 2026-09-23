package com.example.backend.dto.respones;

public class CurrentUserResponse {

    private Long userId;
    private String account;
    private String name;
    private String role;

    public CurrentUserResponse() {
    }

    public CurrentUserResponse(Long userId, String account, String name, String role) {
        this.userId = userId;
        this.account = account;
        this.name = name;
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

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }
}
