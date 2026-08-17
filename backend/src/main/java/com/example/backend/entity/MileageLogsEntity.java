package com.example.backend.entity;

import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 里程紀錄。司機出車與收工各填一次里程表讀數。
 * 實際里程 = endOdometer - startOdometer
 * 實際工時 = endTime - startTime
 */
@Entity
@Table(name = "mileage_logs")
public class MileageLogsEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long driverId;

    @Column(nullable = false)
    private LocalDate date;

    @Column
    private Integer startOdometer;

    @Column
    private Integer endOdometer;

    @Column
    private LocalDateTime startTime;

    @Column
    private LocalDateTime endTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getDriverId() {
        return driverId;
    }

    public void setDriverId(Long driverId) {
        this.driverId = driverId;
    }

    public LocalDate getDate() {
        return date;
    }

    public void setDate(LocalDate date) {
        this.date = date;
    }

    public Integer getStartOdometer() {
        return startOdometer;
    }

    public void setStartOdometer(Integer startOdometer) {
        this.startOdometer = startOdometer;
    }

    public Integer getEndOdometer() {
        return endOdometer;
    }

    public void setEndOdometer(Integer endOdometer) {
        this.endOdometer = endOdometer;
    }

    public LocalDateTime getStartTime() {
        return startTime;
    }

    public void setStartTime(LocalDateTime startTime) {
        this.startTime = startTime;
    }

    public LocalDateTime getEndTime() {
        return endTime;
    }

    public void setEndTime(LocalDateTime endTime) {
        this.endTime = endTime;
    }
}
