package com.example.backend.dto.respones;

import com.example.backend.constants.ScheduleStatus;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/** AI 調度工具使用的司機可派狀態。 */
/**
 * 某一天的司機可派狀況，給 AI 助理挑人用。
 *
 * <p>可派與不可派分成兩份清單，不可派的附上原因：
 * 調度員問「為什麼不能派某某」時，AI 照原因回答就好，不必自己推測。</p>
 */
public class DriverAvailabilityResponse {

    private LocalDate date;

    /** 當月班表狀態；DRAFT 代表班次還可能被調整 */
    private ScheduleStatus scheduleStatus;

    private List<DriverItem> available = new ArrayList<>();

    private List<DriverItem> unavailable = new ArrayList<>();

    public DriverAvailabilityResponse() {
    }

    public LocalDate getDate() {
        return date;
    }

    public void setDate(LocalDate date) {
        this.date = date;
    }

    public ScheduleStatus getScheduleStatus() {
        return scheduleStatus;
    }

    public void setScheduleStatus(ScheduleStatus scheduleStatus) {
        this.scheduleStatus = scheduleStatus;
    }

    public List<DriverItem> getAvailable() {
        return available;
    }

    public void setAvailable(List<DriverItem> available) {
        this.available = available;
    }

    public List<DriverItem> getUnavailable() {
        return unavailable;
    }

    public void setUnavailable(List<DriverItem> unavailable) {
        this.unavailable = unavailable;
    }

    /** 一位司機。可派的帶當天上班時段，不可派的帶原因 */
    public static class DriverItem {

        private Long driverId;

        private String account;

        private String name;

        private LocalTime workStart;

        private LocalTime workEnd;

        private String reason;

        public DriverItem() {
        }

        public Long getDriverId() {
            return driverId;
        }

        public void setDriverId(Long driverId) {
            this.driverId = driverId;
        }

        public String getAccount() {
            return account;
        }

        public void setAccount(String account) {
            this.account = account;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public LocalTime getWorkStart() {
            return workStart;
        }

        public void setWorkStart(LocalTime workStart) {
            this.workStart = workStart;
        }

        public LocalTime getWorkEnd() {
            return workEnd;
        }

        public void setWorkEnd(LocalTime workEnd) {
            this.workEnd = workEnd;
        }

        public String getReason() {
            return reason;
        }

        public void setReason(String reason) {
            this.reason = reason;
        }
    }
}
