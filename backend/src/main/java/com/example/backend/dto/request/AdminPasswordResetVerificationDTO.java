package com.example.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class AdminPasswordResetVerificationDTO {

    @NotBlank(message = "帳號不可空白")
    @Size(max = 60, message = "帳號長度不可超過 60 字元")
    private String account;

    @NotBlank(message = "手機號碼不可空白")
    @Size(max = 10, message = "手機號碼長度不可超過 10 字元")
    private String phone;

    public String getAccount() {
        return account;
    }

    public void setAccount(String account) {
        this.account = account;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }
}
