package com.example.backend.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import static com.example.backend.constants.ValidMsg.DRIVER_APPLICATION_ACCOUNT_MAX_LENGTH;
import static com.example.backend.constants.ValidMsg.DRIVER_APPLICATION_ACCOUNT_REQUIRED;
import static com.example.backend.constants.ValidMsg.DRIVER_APPLICATION_NAME_MAX_LENGTH;
import static com.example.backend.constants.ValidMsg.DRIVER_APPLICATION_NAME_REQUIRED;
import static com.example.backend.constants.ValidMsg.DRIVER_APPLICATION_NATIONAL_ID_FORMAT;
import static com.example.backend.constants.ValidMsg.DRIVER_APPLICATION_NATIONAL_ID_REQUIRED;
import static com.example.backend.constants.ValidMsg.DRIVER_APPLICATION_PHONE_FORMAT;
import static com.example.backend.constants.ValidMsg.DRIVER_APPLICATION_PHONE_REQUIRED;

/** 司機本人送出的帳號申請。nationalId 只作為初始密碼，不會明碼保存。 */
public class DriverAccountApplicationRequest {

    @NotBlank(message = DRIVER_APPLICATION_ACCOUNT_REQUIRED)
    @Size(max = 50, message = DRIVER_APPLICATION_ACCOUNT_MAX_LENGTH)
    private String account;

    @NotBlank(message = DRIVER_APPLICATION_NAME_REQUIRED)
    @Size(max = 30, message = DRIVER_APPLICATION_NAME_MAX_LENGTH)
    private String name;

    @NotBlank(message = DRIVER_APPLICATION_PHONE_REQUIRED)
    @Pattern(regexp = "09\\d{8}", message = DRIVER_APPLICATION_PHONE_FORMAT)
    private String phone;

    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @NotBlank(message = DRIVER_APPLICATION_NATIONAL_ID_REQUIRED)
    @Pattern(regexp = "(?i)[A-Z][12]\\d{8}", message = DRIVER_APPLICATION_NATIONAL_ID_FORMAT)
    private String nationalId;

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

    public String getNationalId() {
        return nationalId;
    }

    public void setNationalId(String nationalId) {
        this.nationalId = nationalId;
    }
}
