package com.example.backend.dto.request;

import com.example.backend.constants.LeaveType;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalTime;

public class DriverLeaveRequestDTO {
    @NotNull(message = "請選擇請假日期")
    @FutureOrPresent(message = "不能申請過去日期的請假")
    private LocalDate workDate;

    @NotNull(message = "請選擇病假、年假、特殊事由或生理假")
    private LeaveType leaveType;

    /**
     * 所有假別都能由司機填寫起訖時間；兩者都不填代表整天，兩者都填代表部分時段。
     */
    private LocalTime leaveStart;
    private LocalTime leaveEnd;

    @NotBlank(message = "請填寫請假原因")
    @Size(max = 500, message = "請假原因不能超過 500 字")
    private String reason;

    public LocalDate getWorkDate() { return workDate; }
    public void setWorkDate(LocalDate workDate) { this.workDate = workDate; }
    public LeaveType getLeaveType() { return leaveType; }
    public void setLeaveType(LeaveType leaveType) { this.leaveType = leaveType; }
    public LocalTime getLeaveStart() { return leaveStart; }
    public void setLeaveStart(LocalTime leaveStart) { this.leaveStart = leaveStart; }
    public LocalTime getLeaveEnd() { return leaveEnd; }
    public void setLeaveEnd(LocalTime leaveEnd) { this.leaveEnd = leaveEnd; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
