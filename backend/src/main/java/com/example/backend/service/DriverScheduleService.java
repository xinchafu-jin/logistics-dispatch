package com.example.backend.service;

import com.example.backend.constants.ScheduleStatus;
import com.example.backend.constants.ShiftType;
import com.example.backend.dao.AttendanceRecordsDAO;
import com.example.backend.dao.DriverShiftsDAO;
import com.example.backend.dao.DriversDAO;
import com.example.backend.dao.ScheduleMonthsDAO;
import com.example.backend.dto.request.DriverShiftDTO;
import com.example.backend.dto.request.ScheduleMonthDTO;
import com.example.backend.entity.DriverShiftsEntity;
import com.example.backend.entity.DriversEntity;
import com.example.backend.entity.ScheduleMonthsEntity;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Transactional
public class DriverScheduleService {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    private final ScheduleMonthsDAO scheduleMonthsDAO;
    private final DriverShiftsDAO driverShiftsDAO;
    private final DriversDAO driversDAO;
    private final AttendanceRecordsDAO attendanceRecordsDAO;

    public DriverScheduleService(
            ScheduleMonthsDAO scheduleMonthsDAO,
            DriverShiftsDAO driverShiftsDAO,
            DriversDAO driversDAO,
            AttendanceRecordsDAO attendanceRecordsDAO
    ) {
        this.scheduleMonthsDAO = scheduleMonthsDAO;
        this.driverShiftsDAO = driverShiftsDAO;
        this.driversDAO = driversDAO;
        this.attendanceRecordsDAO = attendanceRecordsDAO;
    }

    /**
     * 每個月底自動建立下下個月班表，例如 7 月底建立 9 月班表。
     * generateMonth 是冪等操作，同一月份重複觸發不會建立第二份班表。
     */
    @Scheduled(cron = "0 0 1 L * *", zone = "Asia/Taipei")
    public void generatePlanningScheduleAtMonthEnd() {
        generateMonth(YearMonth.now(TAIPEI).plusMonths(2));
    }

    public ScheduleMonthDTO generateMonth(YearMonth targetMonth) {
        if (targetMonth == null) {
            throw new IllegalArgumentException("班表月份不能為空");
        }

        LocalDate firstDay = targetMonth.atDay(1);
        return scheduleMonthsDAO.findByScheduleMonth(firstDay)
                .map(this::toMonthDTO)
                .orElseGet(() -> createMonth(targetMonth));
    }

    @Transactional(readOnly = true)
    public ScheduleMonthDTO findMonth(YearMonth targetMonth) {
        if (targetMonth == null) {
            throw new IllegalArgumentException("班表月份不能為空");
        }
        return toMonthDTO(scheduleMonthsDAO.findByScheduleMonth(targetMonth.atDay(1))
                .orElseThrow(() -> new EntityNotFoundException("找不到 " + targetMonth + " 的班表")));
    }

    @Transactional(readOnly = true)
    public List<DriverShiftDTO> findMonthShifts(Long scheduleMonthId) {
        findMonthEntity(scheduleMonthId);
        return driverShiftsDAO.findAllByScheduleMonthIdOrderByWorkDateAscDriverIdAsc(scheduleMonthId)
                .stream()
                .map(this::toShiftDTO)
                .toList();
    }

    /** 主管編輯草稿中的單日班次。 */
    public DriverShiftDTO updateShift(Long shiftId, DriverShiftDTO dto) {
        if (dto == null) {
            throw new IllegalArgumentException("班次資料不能為空");
        }

        DriverShiftsEntity shift = findShiftEntity(shiftId);
        ScheduleMonthsEntity month = findMonthEntity(shift.getScheduleMonthId());
        ensureDraft(month);

        ShiftType shiftType = dto.getShiftType();
        if (shiftType == null) {
            throw new IllegalArgumentException("請選擇上班、休假、請假或尚未安排");
        }

        DriversEntity driver = driversDAO.findById(shift.getDriverId())
                .orElseThrow(() -> new EntityNotFoundException("找不到司機，ID：" + shift.getDriverId()));

        shift.setShiftType(shiftType);
        if (shiftType == ShiftType.WORK) {
            applyWorkingShift(dto, shift, driver);
        } else if (shiftType == ShiftType.DAY_OFF || shiftType == ShiftType.LEAVE) {
            shift.setWorkStart(null);
            shift.setWorkEnd(null);
            shift.setOvertimeMinutes(0);
            if (shiftType == ShiftType.LEAVE) {
                shift.setChangeReason(requireReason(dto.getChangeReason()));
            } else {
                shift.setChangeReason(normalizeReason(dto.getChangeReason(), "主管排定休假"));
            }
        } else {
            shift.setWorkStart(driver.getWorkStart());
            shift.setWorkEnd(driver.getWorkEnd());
            shift.setOvertimeMinutes(0);
            shift.setChangeReason("尚未安排");
        }

        return toShiftDTO(driverShiftsDAO.save(shift));
    }

    /**
     * 班表草稿建立後若新增司機，主管可呼叫此方法補齊該司機整月班次。
     * 已發布班表不自動插入新司機，避免司機看到未經主管確認的班次。
     */
    public List<DriverShiftDTO> syncActiveDrivers(Long scheduleMonthId) {
        ScheduleMonthsEntity month = findMonthEntity(scheduleMonthId);
        ensureDraft(month);
        YearMonth targetMonth = YearMonth.from(month.getScheduleMonth());

        List<DriverShiftsEntity> newShifts = driversDAO.findAllByIsActiveTrueOrderByIdAsc().stream()
                .filter(driver -> !driverShiftsDAO.existsByScheduleMonthIdAndDriverId(
                        scheduleMonthId,
                        driver.getId()
                ))
                .flatMap(driver -> targetMonth.atDay(1).datesUntil(targetMonth.atEndOfMonth().plusDays(1))
                        .map(date -> newUnassignedShift(scheduleMonthId, driver, date)))
                .toList();
        if (!newShifts.isEmpty()) {
            driverShiftsDAO.saveAll(newShifts);
        }

        return findMonthShifts(scheduleMonthId);
    }

    /**
     * 主管將今天或未來的班次改為臨時請假；草稿及已發布班表都可使用。
     * 若司機已打過上班卡，應保留原班次並以實際下班時間記錄提早離開。
     */
    public DriverShiftDTO markLeave(Long shiftId, String reason) {
        DriverShiftsEntity shift = findShiftEntity(shiftId);
        findMonthEntity(shift.getScheduleMonthId());

        if (shift.getWorkDate().isBefore(LocalDate.now(TAIPEI))) {
            throw new IllegalArgumentException("不能把過去的班次改為請假");
        }
        if (attendanceRecordsDAO.existsByDriverShiftId(shiftId)) {
            throw new IllegalArgumentException("司機已打過上班卡，請保留班次並記錄實際下班時間");
        }

        shift.setShiftType(ShiftType.LEAVE);
        shift.setWorkStart(null);
        shift.setWorkEnd(null);
        shift.setOvertimeMinutes(0);
        shift.setChangeReason(requireReason(reason));
        return toShiftDTO(driverShiftsDAO.save(shift));
    }

    /** 發布前要求每一位司機每天都已被標示為上班或休假。 */
    public ScheduleMonthDTO publish(Long scheduleMonthId) {
        ScheduleMonthsEntity month = findMonthEntity(scheduleMonthId);
        ensureDraft(month);

        List<DriverShiftsEntity> shifts =
                driverShiftsDAO.findAllByScheduleMonthIdOrderByWorkDateAscDriverIdAsc(scheduleMonthId);
        if (shifts.isEmpty()) {
            throw new IllegalArgumentException("班表內沒有司機班次，無法發布");
        }

        DriverShiftsEntity unassigned = shifts.stream()
                .filter(shift -> shift.getShiftType() == ShiftType.UNASSIGNED)
                .findFirst()
                .orElse(null);
        if (unassigned != null) {
            throw new IllegalArgumentException(
                    "班表尚未排完：司機 ID " + unassigned.getDriverId()
                            + " 在 " + unassigned.getWorkDate() + " 尚未安排"
            );
        }

        month.setStatus(ScheduleStatus.PUBLISHED);
        month.setPublishedAt(LocalDateTime.now(TAIPEI));
        return toMonthDTO(scheduleMonthsDAO.save(month));
    }

    /** 司機端只能讀取已發布的班表。 */
    @Transactional(readOnly = true)
    public List<DriverShiftDTO> findPublishedForDriver(Long driverId, LocalDate from, LocalDate to) {
        validateDateRange(from, to);
        if (!driversDAO.existsById(driverId)) {
            throw new EntityNotFoundException("找不到司機，ID：" + driverId);
        }

        Map<Long, ScheduleMonthsEntity> months = new HashMap<>();
        return driverShiftsDAO.findAllByDriverIdAndWorkDateBetweenOrderByWorkDateAsc(driverId, from, to)
                .stream()
                .filter(shift -> months.computeIfAbsent(
                        shift.getScheduleMonthId(),
                        this::findMonthEntity
                ).getStatus() == ScheduleStatus.PUBLISHED)
                .map(this::toShiftDTO)
                .toList();
    }

    private ScheduleMonthDTO createMonth(YearMonth targetMonth) {
        LocalDateTime now = LocalDateTime.now(TAIPEI);
        ScheduleMonthsEntity month = new ScheduleMonthsEntity();
        month.setScheduleMonth(targetMonth.atDay(1));
        month.setStatus(ScheduleStatus.DRAFT);
        month.setGeneratedAt(now);
        month = scheduleMonthsDAO.save(month);

        Long monthId = month.getId();
        List<DriverShiftsEntity> shifts = driversDAO.findAllByIsActiveTrueOrderByIdAsc().stream()
                .flatMap(driver -> targetMonth.atDay(1).datesUntil(targetMonth.atEndOfMonth().plusDays(1))
                        .map(date -> newUnassignedShift(monthId, driver, date)))
                .toList();
        driverShiftsDAO.saveAll(shifts);

        return toMonthDTO(month);
    }

    private DriverShiftsEntity newUnassignedShift(Long monthId, DriversEntity driver, LocalDate date) {
        DriverShiftsEntity shift = new DriverShiftsEntity();
        shift.setScheduleMonthId(monthId);
        shift.setDriverId(driver.getId());
        shift.setWorkDate(date);
        shift.setShiftType(ShiftType.UNASSIGNED);
        shift.setWorkStart(driver.getWorkStart());
        shift.setWorkEnd(driver.getWorkEnd());
        shift.setOvertimeMinutes(0);
        shift.setChangeReason("系統建立班表");
        return shift;
    }

    private void applyWorkingShift(DriverShiftDTO dto, DriverShiftsEntity shift, DriversEntity driver) {
        LocalTime workStart = dto.getWorkStart();
        LocalTime workEnd = dto.getWorkEnd();
        if (workStart == null || workEnd == null) {
            throw new IllegalArgumentException("上班日必須設定上班與下班時間");
        }
        if (!workEnd.isAfter(workStart)) {
            throw new IllegalArgumentException("下班時間必須晚於上班時間");
        }

        int overtimeMinutes = dto.getOvertimeMinutes() == null ? 0 : dto.getOvertimeMinutes();
        if (overtimeMinutes < 0) {
            throw new IllegalArgumentException("加班分鐘數不能小於 0");
        }
        int maxOvertime = driver.getMaxOvertimeMinutes() == null ? 0 : driver.getMaxOvertimeMinutes();
        if (overtimeMinutes > maxOvertime) {
            throw new IllegalArgumentException("加班分鐘數超過司機上限：" + maxOvertime + " 分鐘");
        }

        shift.setWorkStart(workStart);
        shift.setWorkEnd(workEnd);
        shift.setOvertimeMinutes(overtimeMinutes);
        shift.setChangeReason(normalizeReason(dto.getChangeReason(), "主管安排上班"));
    }

    private String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("請假原因不能為空");
        }
        String normalized = reason.trim();
        if (normalized.length() > 255) {
            throw new IllegalArgumentException("修改原因不能超過 255 字元");
        }
        return normalized;
    }

    private String normalizeReason(String reason, String defaultReason) {
        if (reason == null || reason.isBlank()) {
            return defaultReason;
        }
        String normalized = reason.trim();
        if (normalized.length() > 255) {
            throw new IllegalArgumentException("修改原因不能超過 255 字元");
        }
        return normalized;
    }

    private void ensureDraft(ScheduleMonthsEntity month) {
        if (month.getStatus() != ScheduleStatus.DRAFT) {
            throw new IllegalArgumentException("班表已發布，不能再直接修改");
        }
    }

    private void validateDateRange(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw new IllegalArgumentException("班表起訖日期不能為空");
        }
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("班表起始日期不能晚於結束日期");
        }
    }

    private ScheduleMonthsEntity findMonthEntity(Long id) {
        return scheduleMonthsDAO.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("找不到班表，ID：" + id));
    }

    private DriverShiftsEntity findShiftEntity(Long id) {
        return driverShiftsDAO.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("找不到班次，ID：" + id));
    }

    private ScheduleMonthDTO toMonthDTO(ScheduleMonthsEntity entity) {
        ScheduleMonthDTO dto = new ScheduleMonthDTO();
        dto.setId(entity.getId());
        dto.setScheduleMonth(entity.getScheduleMonth());
        dto.setStatus(entity.getStatus());
        dto.setGeneratedAt(entity.getGeneratedAt());
        dto.setPublishedAt(entity.getPublishedAt());
        return dto;
    }

    private DriverShiftDTO toShiftDTO(DriverShiftsEntity entity) {
        DriverShiftDTO dto = new DriverShiftDTO();
        dto.setId(entity.getId());
        dto.setScheduleMonthId(entity.getScheduleMonthId());
        dto.setDriverId(entity.getDriverId());
        dto.setWorkDate(entity.getWorkDate());
        dto.setShiftType(entity.getShiftType());
        dto.setWorkStart(entity.getWorkStart());
        dto.setWorkEnd(entity.getWorkEnd());
        dto.setOvertimeMinutes(entity.getOvertimeMinutes());
        dto.setChangeReason(entity.getChangeReason());
        dto.setLastModifiedAt(entity.getLastModifiedAt());
        dto.setVersion(entity.getVersion());
        return dto;
    }
}
