package com.example.backend.dto.request;

import com.example.backend.constants.LeaveType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

/** 司機預排請假；同一 Group 的假別與原因相同，可一次選取多個日期。 */
public class DriverPlannedLeaveBatchRequestDTO {
    @NotEmpty(message = "至少要有一組預排請假")
    @Size(max = 20, message = "一次最多送出 20 組預排請假")
    private List<@Valid Group> groups;

    public List<Group> getGroups() { return groups; }
    public void setGroups(List<Group> groups) { this.groups = groups; }

    public static class Group {
        @NotNull(message = "請選擇假別")
        private LeaveType leaveType;

        @NotEmpty(message = "至少要選擇一個請假日期")
        @Size(max = 62, message = "每組一次最多選擇 62 天")
        private List<@NotNull(message = "請假日期不能為空") LocalDate> workDates;

        @NotBlank(message = "請填寫請假原因")
        @Size(max = 500, message = "請假原因不能超過 500 字")
        private String reason;

        public LeaveType getLeaveType() { return leaveType; }
        public void setLeaveType(LeaveType leaveType) { this.leaveType = leaveType; }
        public List<LocalDate> getWorkDates() { return workDates; }
        public void setWorkDates(List<LocalDate> workDates) { this.workDates = workDates; }
        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
    }
}
