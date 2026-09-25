package com.example.backend.dto.request;

import com.example.backend.constants.LeaveType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public class LeaveTypeCorrectionDTO {
    @NotNull(message = "請選擇修正後的假別")
    private LeaveType leaveType;

    @NotBlank(message = "修正假別必須填寫原因")
    @Size(max = 500, message = "修正原因不能超過 500 字")
    private String reason;

    public LeaveType getLeaveType() { return leaveType; }
    public void setLeaveType(LeaveType leaveType) { this.leaveType = leaveType; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
