package com.example.backend.dao;

import com.example.backend.entity.DriverShiftsEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalTime;
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

    /** 可先選過去上班日（含有打卡）；送出時另檢查整天／部分時段資格與重複申請。 */
    @Query("""
            select shift.workDate from DriverShiftsEntity shift
            join ScheduleMonthsEntity month on month.id = shift.scheduleMonthId
            where shift.driverId = :driverId and shift.workDate between :from and :to
              and shift.shiftType = com.example.backend.constants.ShiftType.WORK
              and month.status = com.example.backend.constants.ScheduleStatus.PUBLISHED
              and (:fullDay = true or ((shift.workStart is null or shift.workStart <= :leaveStart)
                              and (shift.workEnd is null or shift.workEnd >= :leaveEnd)))
              and not exists (select request.id from DriverLeaveRequestsEntity request
                  where request.driverId = shift.driverId and request.workDate = shift.workDate
                    and request.status <> com.example.backend.constants.LeaveRequestStatus.REJECTED
                    and (request.status = com.example.backend.constants.LeaveRequestStatus.PENDING
                      or :fullDay = true or request.fullDay = true
                      or request.leaveStart is null or request.leaveEnd is null
                      or (request.leaveStart < :leaveEnd and :leaveStart < request.leaveEnd))
                    and not (request.status = com.example.backend.constants.LeaveRequestStatus.PENDING
                      and (request.requestMode = com.example.backend.constants.LeaveRequestMode.SYSTEM_NO_SHOW
                        or request.requestMode = com.example.backend.constants.LeaveRequestMode.TEMPORARY)
                      and request.submissionSource = com.example.backend.constants.LeaveSubmissionSource.SYSTEM
                      and request.fullDay = true))
            order by shift.workDate
            """)
    List<LocalDate> findMakeupCandidateDates(@Param("driverId") Long driverId,
            @Param("from") LocalDate from, @Param("to") LocalDate to,
            @Param("fullDay") boolean fullDay, @Param("leaveStart") LocalTime leaveStart,
            @Param("leaveEnd") LocalTime leaveEnd);

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
