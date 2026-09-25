package com.example.backend.dto.request;

import com.example.backend.constants.LeaveType;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalTime;

public class PlannedPartialLeaveRequestDTO {
    @NotNull(message = "請選擇司機")
    private Long driverId;
    @NotNull(message = "請選擇預排假日期")
    @FutureOrPresent(message = "不能新增過去日期的預排假")
    private LocalDate workDate;
    @NotNull(message = "請選擇假別")
    private LeaveType leaveType;
    @NotNull(message = "請填寫預排假開始時間")
    private LocalTime leaveStart;
    @NotNull(message = "請填寫預排假結束時間")
    private LocalTime leaveEnd;
    @NotBlank(message = "請填寫預排假原因")
    @Size(max = 500, message = "預排假原因不能超過 500 字")
    private String reason;

    public Long getDriverId() { return driverId; }
    public void setDriverId(Long driverId) { this.driverId = driverId; }
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
