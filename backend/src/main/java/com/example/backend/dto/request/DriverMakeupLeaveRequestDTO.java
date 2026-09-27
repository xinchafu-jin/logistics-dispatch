package com.example.backend.dto.request;

import com.example.backend.constants.LeaveType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalTime;

/** 過去上班日補請：起訖皆留白代表整天，有打卡時只能補部分時段。 */
public class DriverMakeupLeaveRequestDTO {
    @NotNull(message = "請選擇補請假日期")
    @Past(message = "事後補請假只能選擇今天以前的日期")
    private LocalDate workDate;

    @NotNull(message = "請選擇假別")
    private LeaveType leaveType;

    private LocalTime leaveStart;
    private LocalTime leaveEnd;

    @NotBlank(message = "請填寫補請假原因")
    @Size(max = 500, message = "補請假原因不能超過 500 字")
    private String reason;

    @Size(max = 500, message = "佐證照片網址不能超過 500 字")
    private String evidencePhotoUrl;

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
    public String getEvidencePhotoUrl() { return evidencePhotoUrl; }
    public void setEvidencePhotoUrl(String evidencePhotoUrl) { this.evidencePhotoUrl = evidencePhotoUrl; }
}
