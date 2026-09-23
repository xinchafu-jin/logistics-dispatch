package com.example.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 已登入主管修改自己密碼時使用。 */
public class AdminPasswordChangeDTO {

    @NotBlank(message = "原密碼不可空白")
    private String currentPassword;

    @NotBlank(message = "新密碼不可空白")
    @Size(min = 8, max = 12, message = "密碼長度必須介於 8 到 12 字元")
    private String newPassword;

    public String getCurrentPassword() {
        return currentPassword;
    }

    public void setCurrentPassword(String currentPassword) {
        this.currentPassword = currentPassword;
    }

    public String getNewPassword() {
        return newPassword;
    }

    public void setNewPassword(String newPassword) {
        this.newPassword = newPassword;
    }
}
