package com.example.backend.dto.respones;

/** 個人資料頁顯示的自己的資料；姓名以資料庫為準，不是登入 Token 裡的。 */
public class AdminProfileResponse {

    private String account;
    private String name;
    private String phone;

    public AdminProfileResponse(String account, String name, String phone) {
        this.account = account;
        this.name = name;
        this.phone = phone;
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
}
