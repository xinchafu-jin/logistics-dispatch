package com.example.backend.dao;

import com.example.backend.entity.ScheduleMonthsEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;

@Repository
public interface ScheduleMonthsDAO extends JpaRepository<ScheduleMonthsEntity, Long> {

    Optional<ScheduleMonthsEntity> findByScheduleMonth(LocalDate scheduleMonth);
}
