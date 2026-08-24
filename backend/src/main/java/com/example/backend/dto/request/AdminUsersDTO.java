package com.example.backend.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class AdminUsersDTO {

    private Long id;

    @NotBlank(message = "主管帳號不可空白")
    @Size(max = 60, message = "主管帳號長度不可超過 60 字元")
    private String account;

    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @NotBlank(message = "主管密碼不可空白")
    @Size(min = 8, max = 12, message = "密碼長度必須介於 8 到 12 字元")
    private String password;

    @NotBlank(message = "主管姓名不可空白")
    @Size(max = 60, message = "主管姓名長度不可超過 60 字元")
    private String name;

    @NotBlank(message = "主管手機號碼不可空白")
    @Size(max = 10, message = "手機號碼長度不可超過 10 字元")
    private String phone;

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
}
