package com.example.backend.service;

import com.example.backend.constants.AttendanceStatus;
import com.example.backend.constants.AttendancePunctualityStatus;
import com.example.backend.constants.EmergencyLeaveStatus;
import com.example.backend.constants.ScheduleStatus;
import com.example.backend.constants.ShiftType;
import com.example.backend.dao.AttendanceRecordsDAO;
import com.example.backend.dao.DriverShiftsDAO;
import com.example.backend.dao.DriversDAO;
import com.example.backend.dao.EmergencyLeaveRequestsDAO;
import com.example.backend.dao.MileageLogsDAO;
import com.example.backend.dao.ScheduleMonthsDAO;
import com.example.backend.dto.request.AttendanceRecordDTO;
import com.example.backend.entity.AttendanceRecordsEntity;
import com.example.backend.entity.DriverShiftsEntity;
import com.example.backend.entity.DriversEntity;
import com.example.backend.entity.ScheduleMonthsEntity;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.YearMonth;
import java.util.Objects;
import java.util.Optional;

@Service
@Transactional
public class AttendanceService {

    public static final int BREAK_DURATION_MINUTES = 60;
    public static final int OVERTIME_THRESHOLD_MINUTES = 30;
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    private final AttendanceRecordsDAO attendanceRecordsDAO;
    private final DriverShiftsDAO driverShiftsDAO;
    private final ScheduleMonthsDAO scheduleMonthsDAO;
    private final DriversDAO driversDAO;
    private final MileageLogsDAO mileageLogsDAO;
    private final EmergencyLeaveRequestsDAO emergencyLeaveRequestsDAO;
    private final WarehouseProximityService warehouseProximityService;

    public AttendanceService(
            AttendanceRecordsDAO attendanceRecordsDAO,
            DriverShiftsDAO driverShiftsDAO,
            ScheduleMonthsDAO scheduleMonthsDAO,
            DriversDAO driversDAO,
            MileageLogsDAO mileageLogsDAO,
            EmergencyLeaveRequestsDAO emergencyLeaveRequestsDAO,
            WarehouseProximityService warehouseProximityService
    ) {
        this.attendanceRecordsDAO = attendanceRecordsDAO;
        this.driverShiftsDAO = driverShiftsDAO;
        this.scheduleMonthsDAO = scheduleMonthsDAO;
        this.driversDAO = driversDAO;
        this.mileageLogsDAO = mileageLogsDAO;
        this.emergencyLeaveRequestsDAO = emergencyLeaveRequestsDAO;
        this.warehouseProximityService = warehouseProximityService;
    }

    public AttendanceRecordDTO clockIn(Long driverId) {
        LocalDateTime now = now();
        LocalDate workDate = now.toLocalDate();
        DriversEntity driver = findActiveDriver(driverId);
        DriverShiftsEntity shift = findPublishedWorkingShift(driver.getId(), workDate);

        if (attendanceRecordsDAO.findForUpdate(driverId, workDate).isPresent()) {
            throw new IllegalArgumentException("今天已經打過上班卡");
        }

        AttendanceRecordsEntity attendance = new AttendanceRecordsEntity();
        attendance.setDriverShiftId(shift.getId());
        attendance.setDriverId(driverId);
        attendance.setWorkDate(workDate);
        attendance.setClockInAt(now);
        attendance.setBreakUsed(false);
        attendance.setStatus(AttendanceStatus.WORKING);
        applyPunctuality(attendance, shift, now);
        return toDTO(attendanceRecordsDAO.save(attendance), now);
    }

    public AttendanceRecordDTO startBreak(Long driverId) {
        LocalDateTime now = now();
        AttendanceRecordsEntity attendance = findTodayForUpdate(driverId, now.toLocalDate());
        refreshAttendanceState(attendance, now);

        if (attendance.getStatus() == AttendanceStatus.CLOCKED_OUT) {
            throw new IllegalArgumentException("已經打下班卡，不能開始休息");
        }
        if (Boolean.TRUE.equals(attendance.getBreakUsed())) {
            throw new IllegalArgumentException("每個班次只能休息一次");
        }
        if (attendance.getStatus() != AttendanceStatus.WORKING
                && attendance.getStatus() != AttendanceStatus.OVERTIME) {
            throw new IllegalArgumentException("目前狀態不能開始休息");
        }

        attendance.setBreakUsed(true);
        attendance.setBreakStartedAt(now);
        attendance.setBreakEndsAt(now.plusMinutes(BREAK_DURATION_MINUTES));
        attendance.setStatus(AttendanceStatus.ON_BREAK);
        return toDTO(attendanceRecordsDAO.save(attendance), now);
    }

    public AttendanceRecordDTO clockOut(Long driverId) {
        LocalDateTime now = now();
        AttendanceRecordsEntity attendance = findTodayForUpdate(driverId, now.toLocalDate());
        if (attendance.getStatus() == AttendanceStatus.CLOCKED_OUT) {
            throw new IllegalArgumentException("今天已經打過下班卡");
        }

        var mileage = mileageLogsDAO.findForUpdate(driverId, now.toLocalDate())
                .orElseThrow(() -> new IllegalArgumentException("今天尚未開始里程，不能打下班卡"));
        if (mileage.getEndTime() == null) {
            throw new IllegalArgumentException("請先按結束里程，完成收車紀錄後才能打下班卡");
        }
        warehouseProximityService.requireWithinWarehouseRadius(driverId, mileage.getRouteId(), now);

        attendance.setClockOutAt(now);
        attendance.setStatus(AttendanceStatus.CLOCKED_OUT);
        updateWorkTimeFields(attendance, now);
        AttendanceRecordDTO result = toDTO(attendanceRecordsDAO.save(attendance), now);
        emergencyLeaveRequestsDAO
                .findFirstByDriverIdAndWorkDateAndStatusOrderByRequestedAtDesc(
                        driverId, now.toLocalDate(), EmergencyLeaveStatus.APPROVED)
                .filter(request -> request.getClockedOutAt() == null)
                .ifPresent(request -> {
                    request.setClockedOutAt(now);
                    emergencyLeaveRequestsDAO.save(request);
                });
        return result;
    }

    public Optional<AttendanceRecordDTO> findToday(Long driverId) {
        LocalDateTime now = now();
        return attendanceRecordsDAO.findForUpdate(driverId, now.toLocalDate())
                .map(attendance -> {
                    refreshAttendanceState(attendance, now);
                    return toDTO(attendance, now);
                });
    }

    /** 與營運報表一致：只結算有效上下班卡，不採用可能過期的即時加班快取。 */
    public int monthlyOvertimeMinutes(Long driverId) {
        return monthlyOvertimeMinutes(driverId, now());
    }

    int monthlyOvertimeMinutes(Long driverId, LocalDateTime asOf) {
        return monthlyOvertimeSummary(driverId, asOf).minutes();
    }

    public MonthlyOvertimeSummary monthlyOvertimeSummary(Long driverId) {
        return monthlyOvertimeSummary(driverId, now());
    }

    MonthlyOvertimeSummary monthlyOvertimeSummary(Long driverId, LocalDateTime asOf) {
        LocalDate today = asOf.toLocalDate();
        LocalDate firstDay = YearMonth.from(today).atDay(1);
        long minutes = 0;
        int unsettled = 0;
        for (var record : attendanceRecordsDAO.findByDriverIdAndWorkDateBetweenOrderByWorkDateAsc(driverId, firstDay, today)) {
            var shift = driverShiftsDAO.findById(record.getDriverShiftId()).orElse(null);
            if (shift == null || shift.getShiftType() != ShiftType.WORK || shift.getWorkStart() == null
                    || shift.getWorkEnd() == null || !driverId.equals(shift.getDriverId())) continue;
            var month = scheduleMonthsDAO.findById(shift.getScheduleMonthId()).orElse(null);
            if (month == null || month.getStatus() != ScheduleStatus.PUBLISHED) continue;
            Long observed = RecordedOvertime.minutes(record.getClockInAt(), record.getClockOutAt(),
                    resolveScheduledEnd(record.getWorkDate(), shift), record.getBreakStartedAt(), record.getBreakEndsAt(), asOf);
            if (observed == null) unsettled++;
            else minutes += observed;
        }
        return new MonthlyOvertimeSummary(Math.toIntExact(minutes), unsettled);
    }

    public record MonthlyOvertimeSummary(int minutes, int unsettledShifts) {}

    /** GPS 寫入前的後端防線：只有工作中才允許，休息與下班一律拒絕。 */
    public boolean isGpsUploadAllowed(Long driverId) {
        LocalDateTime now = now();
        return attendanceRecordsDAO.findForUpdate(driverId, now.toLocalDate())
                .map(attendance -> {
                    refreshAttendanceState(attendance, now);
                    return attendance.getStatus() == AttendanceStatus.WORKING
                            || attendance.getStatus() == AttendanceStatus.OVERTIME;
                })
                .orElse(false);
    }

    /** 每分鐘更新出勤時數；表定下班後每滿 30 分鐘計入一段加班。 */
    @Scheduled(cron = "0 * * * * *", zone = "Asia/Taipei")
    public void refreshOpenAttendanceWorkTimes() {
        LocalDateTime now = now();
        for (AttendanceRecordsEntity attendance :
                attendanceRecordsDAO.findOpenForUpdate(now.toLocalDate())) {
            refreshAttendanceState(attendance, now);
        }
    }

    private DriverShiftsEntity findPublishedWorkingShift(Long driverId, LocalDate workDate) {
        DriverShiftsEntity shift = driverShiftsDAO.findByDriverIdAndWorkDate(driverId, workDate)
                .orElseThrow(() -> new IllegalArgumentException("今天沒有班表，不能打上班卡"));
        ScheduleMonthsEntity month = scheduleMonthsDAO.findById(shift.getScheduleMonthId())
                .orElseThrow(() -> new EntityNotFoundException("找不到班次所屬班表"));

        if (month.getStatus() != ScheduleStatus.PUBLISHED) {
            throw new IllegalArgumentException("今天的班表尚未發布，不能打上班卡");
        }
        if (shift.getShiftType() != ShiftType.WORK) {
            throw new IllegalArgumentException("今天不是上班日，不能打上班卡");
        }
        return shift;
    }

    private DriversEntity findActiveDriver(Long driverId) {
        DriversEntity driver = driversDAO.findById(driverId)
                .orElseThrow(() -> new EntityNotFoundException("找不到司機，ID：" + driverId));
        if (!Boolean.TRUE.equals(driver.getIsActive())) {
            throw new IllegalArgumentException("司機帳號目前未啟用");
        }
        return driver;
    }

    private AttendanceRecordsEntity findTodayForUpdate(Long driverId, LocalDate workDate) {
        return attendanceRecordsDAO.findForUpdate(driverId, workDate)
                .orElseThrow(() -> new IllegalArgumentException("尚未打上班卡"));
    }

    private void refreshAttendanceState(AttendanceRecordsEntity attendance, LocalDateTime now) {
        boolean changed = refreshExpiredBreak(attendance, now);
        changed = updateWorkTimeFields(attendance, now) || changed;

        if (attendance.getClockOutAt() == null && attendance.getStatus() != AttendanceStatus.ON_BREAK) {
            AttendanceStatus expectedStatus = attendance.getOvertimeMinutes() >= OVERTIME_THRESHOLD_MINUTES
                    ? AttendanceStatus.OVERTIME
                    : AttendanceStatus.WORKING;
            if (attendance.getStatus() != expectedStatus) {
                attendance.setStatus(expectedStatus);
                changed = true;
            }
        }

        if (changed) {
            attendanceRecordsDAO.save(attendance);
        }
    }

    private boolean refreshExpiredBreak(AttendanceRecordsEntity attendance, LocalDateTime now) {
        if (attendance.getStatus() == AttendanceStatus.ON_BREAK
                && attendance.getBreakEndsAt() != null
                && !now.isBefore(attendance.getBreakEndsAt())) {
            attendance.setStatus(AttendanceStatus.WORKING);
            return true;
        }
        return false;
    }

    private boolean updateWorkTimeFields(AttendanceRecordsEntity attendance, LocalDateTime now) {
        WorkTimeSummary summary = calculateWorkTime(attendance, now);
        boolean changed = false;

        if (!Objects.equals(attendance.getRegularWorkMinutes(), summary.getRegularWorkMinutes())) {
            attendance.setRegularWorkMinutes(summary.getRegularWorkMinutes());
            changed = true;
        }
        if (!Objects.equals(attendance.getOvertimeMinutes(), summary.getOvertimeMinutes())) {
            attendance.setOvertimeMinutes(summary.getOvertimeMinutes());
            changed = true;
        }
        if (!Objects.equals(attendance.getTotalWorkMinutes(), summary.getTotalWorkMinutes())) {
            attendance.setTotalWorkMinutes(summary.getTotalWorkMinutes());
            changed = true;
        }
        if (!Objects.equals(attendance.getOvertimeStartedAt(), summary.getOvertimeStartedAt())) {
            attendance.setOvertimeStartedAt(summary.getOvertimeStartedAt());
            changed = true;
        }
        return changed;
    }

    private WorkTimeSummary calculateWorkTime(AttendanceRecordsEntity attendance, LocalDateTime now) {
        LocalDateTime effectiveEnd = attendance.getClockOutAt() == null ? now : attendance.getClockOutAt();
        int totalMinutes = workedMinutesBetween(attendance.getClockInAt(), effectiveEnd, attendance);

        DriverShiftsEntity shift = driverShiftsDAO.findById(attendance.getDriverShiftId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "找不到出勤紀錄所屬班次，ID：" + attendance.getDriverShiftId()));
        if (shift.getWorkEnd() == null) {
            return new WorkTimeSummary(totalMinutes, 0, totalMinutes, null);
        }

        LocalDateTime scheduledEnd = resolveScheduledEnd(attendance.getWorkDate(), shift);
        LocalDateTime overtimeRangeStart = attendance.getClockInAt().isAfter(scheduledEnd)
                ? attendance.getClockInAt()
                : scheduledEnd;
        int afterShiftMinutes = workedMinutesBetween(overtimeRangeStart, effectiveEnd, attendance);

        int completedOvertimeMinutes =
                afterShiftMinutes / OVERTIME_THRESHOLD_MINUTES * OVERTIME_THRESHOLD_MINUTES;
        if (completedOvertimeMinutes == 0) {
            return new WorkTimeSummary(totalMinutes, 0, totalMinutes, null);
        }

        int regularMinutes = Math.max(0, totalMinutes - completedOvertimeMinutes);
        long breakAfterShift = breakOverlapMinutes(overtimeRangeStart, effectiveEnd, attendance);
        LocalDateTime overtimeStartedAt = attendance.getOvertimeStartedAt() == null
                ? overtimeRangeStart.plusMinutes(OVERTIME_THRESHOLD_MINUTES + breakAfterShift)
                : attendance.getOvertimeStartedAt();
        return new WorkTimeSummary(regularMinutes, completedOvertimeMinutes, totalMinutes, overtimeStartedAt);
    }

    private LocalDateTime resolveScheduledEnd(LocalDate workDate, DriverShiftsEntity shift) {
        LocalDateTime scheduledEnd = workDate.atTime(shift.getWorkEnd());
        LocalTime workStart = shift.getWorkStart();
        if (workStart != null && !shift.getWorkEnd().isAfter(workStart)) {
            scheduledEnd = scheduledEnd.plusDays(1);
        }
        return scheduledEnd;
    }

    private int workedMinutesBetween(
            LocalDateTime start,
            LocalDateTime end,
            AttendanceRecordsEntity attendance
    ) {
        if (start == null || end == null || !end.isAfter(start)) {
            return 0;
        }
        long elapsed = Duration.between(start, end).toMinutes();
        long breakMinutes = breakOverlapMinutes(start, end, attendance);
        return Math.toIntExact(Math.max(0, elapsed - breakMinutes));
    }

    private long breakOverlapMinutes(
            LocalDateTime rangeStart,
            LocalDateTime rangeEnd,
            AttendanceRecordsEntity attendance
    ) {
        LocalDateTime breakStart = attendance.getBreakStartedAt();
        LocalDateTime breakEnd = attendance.getBreakEndsAt();
        if (breakStart == null || breakEnd == null || !rangeEnd.isAfter(rangeStart)) {
            return 0;
        }

        LocalDateTime overlapStart = breakStart.isAfter(rangeStart) ? breakStart : rangeStart;
        LocalDateTime overlapEnd = breakEnd.isBefore(rangeEnd) ? breakEnd : rangeEnd;
        return overlapEnd.isAfter(overlapStart)
                ? Duration.between(overlapStart, overlapEnd).toMinutes()
                : 0;
    }

    private LocalDateTime now() {
        return LocalDateTime.now(TAIPEI);
    }

    private AttendanceRecordDTO toDTO(AttendanceRecordsEntity entity, LocalDateTime now) {
        AttendanceRecordDTO dto = new AttendanceRecordDTO();
        dto.setId(entity.getId());
        dto.setDriverShiftId(entity.getDriverShiftId());
        dto.setDriverId(entity.getDriverId());
        dto.setWorkDate(entity.getWorkDate());
        dto.setClockInAt(entity.getClockInAt());
        dto.setClockOutAt(entity.getClockOutAt());
        dto.setBreakUsed(entity.getBreakUsed());
        dto.setBreakStartedAt(entity.getBreakStartedAt());
        dto.setBreakEndsAt(entity.getBreakEndsAt());
        dto.setOvertimeStartedAt(entity.getOvertimeStartedAt());
        dto.setRegularWorkMinutes(entity.getRegularWorkMinutes());
        dto.setOvertimeMinutes(entity.getOvertimeMinutes());
        dto.setTotalWorkMinutes(entity.getTotalWorkMinutes());
        dto.setRegularWorkHours(toHours(entity.getRegularWorkMinutes()));
        dto.setOvertimeHours(toHours(entity.getOvertimeMinutes()));
        dto.setTotalWorkHours(toHours(entity.getTotalWorkMinutes()));
        // 現行司機端僅認得 WORKING/ON_BREAK/CLOCKED_OUT；實際加班分鐘仍由後端紀錄。
        dto.setStatus(entity.getStatus() == AttendanceStatus.OVERTIME
                ? AttendanceStatus.WORKING : entity.getStatus());
        dto.setGpsAllowed(entity.getStatus() == AttendanceStatus.WORKING
                || entity.getStatus() == AttendanceStatus.OVERTIME);
        dto.setPunctualityStatus(entity.getPunctualityStatus());
        dto.setLateMinutes(entity.getLateMinutes());
        dto.setLateExcused(entity.getLateExcused());
        dto.setLeaveRequired(entity.getLeaveRequired());
        dto.setLeaveRequiredMinutes(entity.getLeaveRequiredMinutes());
        dto.setCoveredLeaveRequestId(entity.getCoveredLeaveRequestId());

        long remaining = 0;
        if (entity.getStatus() == AttendanceStatus.ON_BREAK && entity.getBreakEndsAt() != null) {
            remaining = Math.max(0, Duration.between(now, entity.getBreakEndsAt()).getSeconds());
        }
        dto.setRemainingBreakSeconds(remaining);
        return dto;
    }

    void applyPunctuality(
            AttendanceRecordsEntity attendance,
            DriverShiftsEntity shift,
            LocalDateTime clockInAt
    ) {
        if (shift.getWorkStart() == null) {
            return;
        }
        LocalDateTime scheduledStart = attendance.getWorkDate().atTime(shift.getWorkStart());
        if (!clockInAt.isAfter(scheduledStart)) {
            return;
        }

        long lateSeconds = Duration.between(scheduledStart, clockInAt).getSeconds();
        int lateMinutes = Math.toIntExact((lateSeconds + 59) / 60);
        attendance.setLateMinutes(lateMinutes);

        if (lateMinutes > 30) {
            attendance.setPunctualityStatus(AttendancePunctualityStatus.LEAVE_REQUIRED);
            attendance.setLeaveRequired(true);
            attendance.setLeaveRequiredMinutes(lateMinutes);
            return;
        }

        YearMonth month = YearMonth.from(attendance.getWorkDate());
        boolean monthlyExcuseUsed = attendanceRecordsDAO
                .existsByDriverIdAndWorkDateBetweenAndLateExcusedTrue(
                        attendance.getDriverId(), month.atDay(1), month.atEndOfMonth());
        if (monthlyExcuseUsed) {
            attendance.setPunctualityStatus(AttendancePunctualityStatus.LATE);
        } else {
            attendance.setPunctualityStatus(AttendancePunctualityStatus.LATE_EXCUSED);
            attendance.setLateExcused(true);
        }
    }

    private BigDecimal toHours(Integer minutes) {
        int safeMinutes = minutes == null ? 0 : minutes;
        return BigDecimal.valueOf(safeMinutes)
                .divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);
    }

    private static final class WorkTimeSummary {

        private final int regularWorkMinutes;
        private final int overtimeMinutes;
        private final int totalWorkMinutes;
        private final LocalDateTime overtimeStartedAt;

        private WorkTimeSummary(
                int regularWorkMinutes,
                int overtimeMinutes,
                int totalWorkMinutes,
                LocalDateTime overtimeStartedAt
        ) {
            this.regularWorkMinutes = regularWorkMinutes;
            this.overtimeMinutes = overtimeMinutes;
            this.totalWorkMinutes = totalWorkMinutes;
            this.overtimeStartedAt = overtimeStartedAt;
        }

        private int getRegularWorkMinutes() {
            return regularWorkMinutes;
        }

        private int getOvertimeMinutes() {
            return overtimeMinutes;
        }

        private int getTotalWorkMinutes() {
            return totalWorkMinutes;
        }

        private LocalDateTime getOvertimeStartedAt() {
            return overtimeStartedAt;
        }
    }
}
