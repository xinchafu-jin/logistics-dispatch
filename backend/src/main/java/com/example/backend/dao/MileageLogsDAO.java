package com.example.backend.dao;

import com.example.backend.entity.MileageLogsEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
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
    List<MileageLogsEntity> findOpenByVehicleForUpdate(@Param("vehicleId") Long vehicleId);

    List<MileageLogsEntity> findByMileageSettledAtIsNullAndEndTimeIsNotNull();

    /** 只取已收車且儀表讀數有效的實際里程；舊紀錄可由綁定路線確認車輛。 */
    @Query("""
            select mileage
            from MileageLogsEntity mileage
            left join RoutesEntity route on route.id = mileage.routeId
            where coalesce(mileage.vehicleId, route.vehicleId) = :vehicleId
              and mileage.endTime is not null
              and mileage.startOdometer is not null
              and mileage.startOdometer >= 0
              and mileage.endOdometer >= mileage.startOdometer
            order by mileage.endTime desc, mileage.id desc
            """)
    List<MileageLogsEntity> findLatestCompletedVehicleMileage(
            @Param("vehicleId") Long vehicleId, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select mileage from MileageLogsEntity mileage where mileage.id = :id")
    Optional<MileageLogsEntity> findByIdForUpdate(@Param("id") Long id);
}
