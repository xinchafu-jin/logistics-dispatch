package com.example.backend.service;

import com.example.backend.constants.AttendanceStatus;
import com.example.backend.constants.ScheduleStatus;
import com.example.backend.constants.ShiftType;
import com.example.backend.dao.AttendanceRecordsDAO;
import com.example.backend.dao.DriverShiftsDAO;
import com.example.backend.dao.DriversDAO;
import com.example.backend.dao.ScheduleMonthsDAO;
import com.example.backend.dto.request.AttendanceRecordDTO;
import com.example.backend.entity.AttendanceRecordsEntity;
import com.example.backend.entity.DriverShiftsEntity;
import com.example.backend.entity.DriversEntity;
import com.example.backend.entity.ScheduleMonthsEntity;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

@Service
@Transactional
public class AttendanceService {

    public static final int BREAK_DURATION_MINUTES = 60;
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    private final AttendanceRecordsDAO attendanceRecordsDAO;
    private final DriverShiftsDAO driverShiftsDAO;
    private final ScheduleMonthsDAO scheduleMonthsDAO;
    private final DriversDAO driversDAO;

    public AttendanceService(
            AttendanceRecordsDAO attendanceRecordsDAO,
            DriverShiftsDAO driverShiftsDAO,
            ScheduleMonthsDAO scheduleMonthsDAO,
            DriversDAO driversDAO
    ) {
        this.attendanceRecordsDAO = attendanceRecordsDAO;
        this.driverShiftsDAO = driverShiftsDAO;
        this.scheduleMonthsDAO = scheduleMonthsDAO;
        this.driversDAO = driversDAO;
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
        return toDTO(attendanceRecordsDAO.save(attendance), now);
    }

    public AttendanceRecordDTO startBreak(Long driverId) {
        LocalDateTime now = now();
        AttendanceRecordsEntity attendance = findTodayForUpdate(driverId, now.toLocalDate());
        refreshExpiredBreak(attendance, now);

        if (attendance.getStatus() == AttendanceStatus.CLOCKED_OUT) {
            throw new IllegalArgumentException("已經打下班卡，不能開始休息");
        }
        if (Boolean.TRUE.equals(attendance.getBreakUsed())) {
            throw new IllegalArgumentException("每個班次只能休息一次");
        }
        if (attendance.getStatus() != AttendanceStatus.WORKING) {
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

        attendance.setClockOutAt(now);
        attendance.setStatus(AttendanceStatus.CLOCKED_OUT);
        return toDTO(attendanceRecordsDAO.save(attendance), now);
    }

    public AttendanceRecordDTO findToday(Long driverId) {
        LocalDateTime now = now();
        AttendanceRecordsEntity attendance = findTodayForUpdate(driverId, now.toLocalDate());
        refreshExpiredBreak(attendance, now);
        return toDTO(attendance, now);
    }

    /** GPS 寫入前的後端防線：只有工作中才允許，休息與下班一律拒絕。 */
    public boolean isGpsUploadAllowed(Long driverId) {
        LocalDateTime now = now();
        return attendanceRecordsDAO.findForUpdate(driverId, now.toLocalDate())
                .map(attendance -> {
                    refreshExpiredBreak(attendance, now);
                    return attendance.getStatus() == AttendanceStatus.WORKING;
                })
                .orElse(false);
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

    private void refreshExpiredBreak(AttendanceRecordsEntity attendance, LocalDateTime now) {
        if (attendance.getStatus() == AttendanceStatus.ON_BREAK
                && attendance.getBreakEndsAt() != null
                && !now.isBefore(attendance.getBreakEndsAt())) {
            attendance.setStatus(AttendanceStatus.WORKING);
            attendanceRecordsDAO.save(attendance);
        }
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
        dto.setStatus(entity.getStatus());
        dto.setGpsAllowed(entity.getStatus() == AttendanceStatus.WORKING);

        long remaining = 0;
        if (entity.getStatus() == AttendanceStatus.ON_BREAK && entity.getBreakEndsAt() != null) {
            remaining = Math.max(0, Duration.between(now, entity.getBreakEndsAt()).getSeconds());
        }
        dto.setRemainingBreakSeconds(remaining);
        return dto;
    }
}
