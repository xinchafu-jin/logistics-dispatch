package com.example.backend.dto.request;

import com.example.backend.constants.ScheduleStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;

public class ScheduleMonthDTO {

    private Long id;
    private LocalDate scheduleMonth;
    private ScheduleStatus status;
    private LocalDateTime generatedAt;
    private LocalDateTime publishedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public LocalDate getScheduleMonth() {
        return scheduleMonth;
    }

    public void setScheduleMonth(LocalDate scheduleMonth) {
        this.scheduleMonth = scheduleMonth;
    }

    public ScheduleStatus getStatus() {
        return status;
    }

    public void setStatus(ScheduleStatus status) {
        this.status = status;
    }

    public LocalDateTime getGeneratedAt() {
        return generatedAt;
    }

    public void setGeneratedAt(LocalDateTime generatedAt) {
        this.generatedAt = generatedAt;
    }

    public LocalDateTime getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(LocalDateTime publishedAt) {
        this.publishedAt = publishedAt;
    }
}
