package com.example.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Min;
import java.time.LocalTime;

public class DriversDTO {

    private Long id;

    @NotBlank(message = "司機帳號不能為空")
    @Size(max = 50, message = "帳號長度不能超過 50 字元")
    private String account;

    @NotBlank(message = "司機姓名不能為空")
    @Size(max = 30, message = "姓名長度不能超過 30 字元")
    private String name;

    @Size(max = 10, message = "電話長度不能超過 10 字元")
    private String phone;

    @NotNull(message = "上班時間不能為空")
    private LocalTime workStart;

    @NotNull(message = "下班時間不能為空")
    private LocalTime workEnd;

    @NotNull(message = "休息時長不能為空")
    @Min(value = 0, message = "休息時長不能小於 0")
    private Integer restDuration;

    @Min(value = 0, message = "加班上限不能小於 0")
    private Integer maxOvertimeMinutes;

    private Boolean isActive;

    // ===== Getter & Setter =====
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getAccount() { return account; }
    public void setAccount(String account) { this.account = account; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public LocalTime getWorkStart() { return workStart; }
    public void setWorkStart(LocalTime workStart) { this.workStart = workStart; }

    public LocalTime getWorkEnd() { return workEnd; }
    public void setWorkEnd(LocalTime workEnd) { this.workEnd = workEnd; }

    public Integer getRestDuration() { return restDuration; }
    public void setRestDuration(Integer restDuration) { this.restDuration = restDuration; }

    public Integer getMaxOvertimeMinutes() { return maxOvertimeMinutes; }
    public void setMaxOvertimeMinutes(Integer maxOvertimeMinutes) { this.maxOvertimeMinutes = maxOvertimeMinutes; }

    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }
}