package com.example.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 已登入主管修改自己的姓名、手機號碼。
 *
 * <p>手機號碼是忘記密碼時驗證身分用的，所以改手機要附目前的密碼（只改姓名不用），
 * 規則見 AdminUsersService.updateProfile。</p>
 */
public class AdminProfileUpdateDTO {

    @NotBlank(message = "姓名不可空白")
    @Size(max = 60, message = "姓名長度不可超過 60 字元")
    private String name;

    // 忘記密碼的 API 限定 10 字元並逐字比對，存進別的格式之後就驗不過
    @NotBlank(message = "手機號碼不可空白")
    @Pattern(regexp = "\\s*09\\d{8}\\s*", message = "手機號碼格式為 09 開頭的 10 碼數字")
    private String phone;

    /** 只有手機號碼有變時才需要 */
    private String currentPassword;

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

    public String getCurrentPassword() {
        return currentPassword;
    }

    public void setCurrentPassword(String currentPassword) {
        this.currentPassword = currentPassword;
    }
}
