package com.example.backend.dto.respones;

import com.example.backend.constants.ScheduleStatus;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/** AI 調度工具使用的司機可派狀態。 */
public class DriverAvailabilityResponse {

    private LocalDate date;
    private ScheduleStatus scheduleStatus;
    private List<DriverItem> available = new ArrayList<>();
    private List<DriverItem> unavailable = new ArrayList<>();

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

    public static class DriverItem {

        private Long driverId;
        private String account;
        private String name;
        private LocalTime workStart;
        private LocalTime workEnd;
        private String reason;

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
