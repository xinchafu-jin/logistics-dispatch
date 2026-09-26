package com.example.backend.dto.request;

import com.example.backend.constants.LeaveType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/** 司機針對已經發生、整天未打卡的上班日補送請假說明。 */
public class DriverMakeupLeaveRequestDTO {
    @NotNull(message = "請選擇補請假日期")
    @Past(message = "事後補請假只能選擇今天以前的日期")
    private LocalDate workDate;

    @NotNull(message = "請選擇假別")
    private LeaveType leaveType;

    @NotBlank(message = "請填寫補請假原因")
    @Size(max = 500, message = "補請假原因不能超過 500 字")
    private String reason;

    @Size(max = 500, message = "佐證照片網址不能超過 500 字")
    private String evidencePhotoUrl;

    public LocalDate getWorkDate() { return workDate; }
    public void setWorkDate(LocalDate workDate) { this.workDate = workDate; }
    public LeaveType getLeaveType() { return leaveType; }
    public void setLeaveType(LeaveType leaveType) { this.leaveType = leaveType; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getEvidencePhotoUrl() { return evidencePhotoUrl; }
    public void setEvidencePhotoUrl(String evidencePhotoUrl) { this.evidencePhotoUrl = evidencePhotoUrl; }
}
