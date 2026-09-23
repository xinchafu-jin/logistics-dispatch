package com.example.backend.dao;

import com.example.backend.constants.EmergencyLeaveStatus;
import com.example.backend.entity.EmergencyLeaveRequestsEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface EmergencyLeaveRequestsDAO extends JpaRepository<EmergencyLeaveRequestsEntity, Long> {
    List<EmergencyLeaveRequestsEntity> findByDriverIdOrderByRequestedAtDesc(Long driverId);
    List<EmergencyLeaveRequestsEntity> findByStatusOrderByRequestedAtAsc(EmergencyLeaveStatus status);
    boolean existsByDriverIdAndWorkDateAndStatus(Long driverId, LocalDate workDate, EmergencyLeaveStatus status);
    boolean existsByDriverIdAndWorkDateAndRouteIdAndStatus(
            Long driverId, LocalDate workDate, Long routeId, EmergencyLeaveStatus status);
    boolean existsByRouteIdAndStatus(Long routeId, EmergencyLeaveStatus status);
    Optional<EmergencyLeaveRequestsEntity> findFirstByDriverIdAndWorkDateAndStatusOrderByRequestedAtDesc(
            Long driverId, LocalDate workDate, EmergencyLeaveStatus status);
    boolean existsByReplacementDriverIdAndWorkDateAndStatusAndRouteReassignedAtIsNull(
            Long replacementDriverId, LocalDate workDate, EmergencyLeaveStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select request from EmergencyLeaveRequestsEntity request where request.id = :id")
    Optional<EmergencyLeaveRequestsEntity> findForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select request from EmergencyLeaveRequestsEntity request
            where request.driverId = :driverId
              and request.workDate = :workDate
              and request.routeId = :routeId
              and request.status = :status
              and request.routeReassignedAt is null
            """)
    Optional<EmergencyLeaveRequestsEntity> findPendingHandoverForUpdate(
            @Param("driverId") Long driverId,
            @Param("workDate") LocalDate workDate,
            @Param("routeId") Long routeId,
            @Param("status") EmergencyLeaveStatus status);
}
