package com.example.backend.dao;

import com.example.backend.constants.LeaveRequestStatus;
import com.example.backend.constants.LeaveSubmissionSource;
import com.example.backend.entity.DriverLeaveRequestsEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface DriverLeaveRequestsDAO extends JpaRepository<DriverLeaveRequestsEntity, Long> {
    List<DriverLeaveRequestsEntity> findByDriverIdOrderByRequestedAtDesc(Long driverId);
    List<DriverLeaveRequestsEntity> findByDriverIdAndWorkDateBetweenOrderByWorkDateAscRequestedAtAsc(
            Long driverId, LocalDate from, LocalDate to);
    List<DriverLeaveRequestsEntity> findByDriverIdAndWorkDateOrderByRequestedAtAsc(
            Long driverId, LocalDate workDate);
    List<DriverLeaveRequestsEntity> findByStatusOrderByRequestedAtAsc(LeaveRequestStatus status);
    List<DriverLeaveRequestsEntity> findByBatchIdOrderByWorkDateAsc(String batchId);
    boolean existsByDriverIdAndWorkDateAndStatus(
            Long driverId, LocalDate workDate, LeaveRequestStatus status);
    boolean existsByDriverShiftIdAndSubmissionSource(
            Long driverShiftId, LeaveSubmissionSource submissionSource);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select request from DriverLeaveRequestsEntity request where request.id = :id")
    Optional<DriverLeaveRequestsEntity> findForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select request from DriverLeaveRequestsEntity request "
            + "where request.driverId = :driverId and request.workDate = :workDate "
            + "order by request.requestedAt asc")
    List<DriverLeaveRequestsEntity> findForUpdateByDriverIdAndWorkDate(
            @Param("driverId") Long driverId,
            @Param("workDate") LocalDate workDate);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select request from DriverLeaveRequestsEntity request "
            + "where request.batchId = :batchId order by request.workDate asc")
    List<DriverLeaveRequestsEntity> findBatchForUpdate(@Param("batchId") String batchId);
}
