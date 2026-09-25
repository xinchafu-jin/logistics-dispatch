package com.example.backend.dao;

import com.example.backend.entity.DriverShiftsEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select shift from DriverShiftsEntity shift
            where shift.driverId = :driverId and shift.workDate = :workDate
            """)
    Optional<DriverShiftsEntity> findForUpdateByDriverIdAndWorkDate(
            @Param("driverId") Long driverId,
            @Param("workDate") LocalDate workDate);

    /** 某天所有司機的班次；發布前檢查一次撈完，不必每條路線各查一次。 */
    List<DriverShiftsEntity> findAllByWorkDate(LocalDate workDate);

    boolean existsByScheduleMonthIdAndDriverId(Long scheduleMonthId, Long driverId);
}
