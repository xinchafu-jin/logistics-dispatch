package com.example.backend.dao;

import com.example.backend.entity.DriverShiftsEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface DriverShiftsDAO extends JpaRepository<DriverShiftsEntity, Long> {

    List<DriverShiftsEntity> findAllByScheduleMonthIdOrderByWorkDateAscDriverIdAsc(Long scheduleMonthId);

    List<DriverShiftsEntity> findAllByDriverIdAndWorkDateBetweenOrderByWorkDateAsc(
            Long driverId,
            LocalDate from,
            LocalDate to
    );

    Optional<DriverShiftsEntity> findByDriverIdAndWorkDate(Long driverId, LocalDate workDate);

    boolean existsByScheduleMonthIdAndDriverId(Long scheduleMonthId, Long driverId);
}
