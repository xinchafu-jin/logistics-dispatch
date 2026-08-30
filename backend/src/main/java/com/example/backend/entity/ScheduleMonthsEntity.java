package com.example.backend.entity;

import com.example.backend.constants.ScheduleStatus;
import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "schedule_months", uniqueConstraints = {
        @UniqueConstraint(name = "uk_schedule_months_month", columnNames = "schedule_month")
})
public class ScheduleMonthsEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 該月第一天，例如 2026-09-01 代表 2026 年 9 月班表。 */
    @Column(name = "schedule_month", nullable = false)
    private LocalDate scheduleMonth;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ScheduleStatus status = ScheduleStatus.DRAFT;

    @Column(nullable = false)
    private LocalDateTime generatedAt;

    @Column
    private LocalDateTime publishedAt;

    @Version
    @Column(nullable = false)
    private Long version;

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

    public Long getVersion() {
        return version;
    }
}
