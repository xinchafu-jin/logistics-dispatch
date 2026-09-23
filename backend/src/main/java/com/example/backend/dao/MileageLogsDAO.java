package com.example.backend.dao;

import com.example.backend.entity.MileageLogsEntity;
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
public interface MileageLogsDAO extends JpaRepository<MileageLogsEntity, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select mileage
            from MileageLogsEntity mileage
            where mileage.driverId = :driverId
              and mileage.date = :date
            """)
    Optional<MileageLogsEntity> findForUpdate(
            @Param("driverId") Long driverId,
            @Param("date") LocalDate date);

    Optional<MileageLogsEntity> findByDriverIdAndDate(Long driverId, LocalDate date);

    List<MileageLogsEntity> findAllByRouteIdOrderByStartTimeAsc(Long routeId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select mileage
            from MileageLogsEntity mileage
            where mileage.vehicleId = :vehicleId
              and mileage.endTime is null
            """)
    List<MileageLogsEntity> findOpenByVehicleForUpdate(
            @Param("vehicleId") Long vehicleId);

    List<MileageLogsEntity> findByMileageSettledAtIsNullAndEndTimeIsNotNull();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select mileage from MileageLogsEntity mileage where mileage.id = :id")
    Optional<MileageLogsEntity> findByIdForUpdate(@Param("id") Long id);
}
