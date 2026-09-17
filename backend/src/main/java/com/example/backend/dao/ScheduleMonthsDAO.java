package com.example.backend.dao;

import com.example.backend.entity.ScheduleMonthsEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;

@Repository
public interface ScheduleMonthsDAO extends JpaRepository<ScheduleMonthsEntity, Long> {

    Optional<ScheduleMonthsEntity> findByScheduleMonth(LocalDate scheduleMonth);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select month from ScheduleMonthsEntity month where month.id = :id")
    Optional<ScheduleMonthsEntity> findForUpdate(@Param("id") Long id);
}
