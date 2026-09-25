package com.example.backend.dto.request;

import com.example.backend.constants.LeaveType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class LeaveDecisionRequestDTO {
    /** 核准時可一併把「特殊事由」改成正式假別或曠職；不填就沿用目前類別。 */
    private LeaveType leaveType;

    @NotBlank(message = "同意或拒絕都必須填寫原因")
    @Size(max = 500, message = "審核原因不能超過 500 字")
    private String reason;

    public LeaveType getLeaveType() { return leaveType; }
    public void setLeaveType(LeaveType leaveType) { this.leaveType = leaveType; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
