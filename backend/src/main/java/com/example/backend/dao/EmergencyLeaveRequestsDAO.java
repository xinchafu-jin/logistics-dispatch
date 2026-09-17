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
    boolean existsByRouteIdAndStatus(Long routeId, EmergencyLeaveStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select request from EmergencyLeaveRequestsEntity request where request.id = :id")
    Optional<EmergencyLeaveRequestsEntity> findForUpdate(@Param("id") Long id);
}
