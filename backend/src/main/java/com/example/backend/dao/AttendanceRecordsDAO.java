package com.example.backend.dao;

import com.example.backend.entity.AttendanceRecordsEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;

@Repository
public interface AttendanceRecordsDAO extends JpaRepository<AttendanceRecordsEntity, Long> {

    Optional<AttendanceRecordsEntity> findByDriverIdAndWorkDate(Long driverId, LocalDate workDate);

    boolean existsByDriverShiftId(Long driverShiftId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from AttendanceRecordsEntity a where a.driverId = :driverId and a.workDate = :workDate")
    Optional<AttendanceRecordsEntity> findForUpdate(
            @Param("driverId") Long driverId,
            @Param("workDate") LocalDate workDate
    );
}
