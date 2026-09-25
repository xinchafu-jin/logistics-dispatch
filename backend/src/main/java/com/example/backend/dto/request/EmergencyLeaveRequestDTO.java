package com.example.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class EmergencyLeaveRequestDTO {
    @NotBlank(message = "請填寫特殊事由原因")
    @Size(max = 500, message = "特殊事由原因不能超過 500 字")
    private String reason;

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
