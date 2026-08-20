package com.example.backend.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Min;
import java.time.LocalTime;
import static com.example.backend.constants.ValidMsg.*;

public class DriversDTO {

    private Long id;

    @NotBlank(message = DRIVER_ACCOUNT_REQUIRED)
    @Size(max = 50, message = DRIVER_ACCOUNT_MAX_LENGTH)
    private String account;

    /**
     * 只接受寫入，回傳司機資料時不會序列化密碼。
     * 新增時 Service 會檢查必填；修改時留空表示不變更密碼。
     */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @Size(min = 8, max = 12, message = "密碼長度必須介於 8 到 12 字元")
    private String password;

    @NotBlank(message = DRIVER_NAME_REQUIRED)
    @Size(max = 30, message = DRIVER_NAME_MAX_LENGTH)
    private String name;

    @Size(max = 10, message = DRIVER_PHONE_MAX_LENGTH)
    private String phone;

    @NotNull(message = DRIVER_WORK_START_REQUIRED)
    private LocalTime workStart;

    @NotNull(message = DRIVER_WORK_END_REQUIRED)
    private LocalTime workEnd;

    @NotNull(message = DRIVER_REST_DURATION_REQUIRED)
    @Min(value = 0, message = DRIVER_REST_DURATION_MIN)
    private Integer restDuration;

    @Min(value = 0, message = DRIVER_OVERTIME_MIN)
    private Integer maxOvertimeMinutes;

    @NotNull(message = DRIVER_ACTIVE_REQUIRED)
    private Boolean isActive = true;

    // ===== Getter & Setter =====
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getAccount() { return account; }
    public void setAccount(String account) { this.account = account; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

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
