package com.example.backend.dto.request;

import com.example.backend.constants.LeaveType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/** 同一假別／原因的多日補請，所有日期在同一交易中送審。 */
public class DriverMakeupLeaveBatchRequestDTO {
    @NotEmpty(message = "至少要選擇一個補請日期")
    @Size(max = 62, message = "一次最多選擇 62 天")
    private List<@NotNull @Past(message = "補請只能選擇今天以前的日期") LocalDate> workDates;
    @NotNull(message = "請選擇假別")
    private LeaveType leaveType;
    /** 多日共用同一時段；兩欄留白代表整天。 */
    private LocalTime leaveStart;
    private LocalTime leaveEnd;
    @NotBlank(message = "請填寫補請原因")
    @Size(max = 500, message = "補請原因不能超過 500 字")
    private String reason;
    @Size(max = 500)
    private String evidencePhotoUrl;

    public List<LocalDate> getWorkDates() { return workDates; }
    public void setWorkDates(List<LocalDate> workDates) { this.workDates = workDates; }
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
