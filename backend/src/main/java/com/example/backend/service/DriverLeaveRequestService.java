package com.example.backend.service;

import com.example.backend.constants.AttendancePunctualityStatus;
import com.example.backend.constants.LeaveRequestStatus;
import com.example.backend.constants.LeaveRequestEventType;
import com.example.backend.constants.LeaveRequestMode;
import com.example.backend.constants.LeaveSubmissionSource;
import com.example.backend.constants.LeaveType;
import com.example.backend.constants.ScheduleStatus;
import com.example.backend.constants.ShiftType;
import com.example.backend.dao.AdminUsersDAO;
import com.example.backend.dao.AttendanceRecordsDAO;
import com.example.backend.dao.DriverLeaveRequestsDAO;
import com.example.backend.dao.DriverLeaveRequestEventsDAO;
import com.example.backend.dao.DriverShiftsDAO;
import com.example.backend.dao.DriversDAO;
import com.example.backend.dao.ScheduleMonthsDAO;
import com.example.backend.dto.request.DriverLeaveRequestDTO;
import com.example.backend.dto.request.DriverMakeupLeaveRequestDTO;
import com.example.backend.dto.request.DriverPlannedLeaveBatchRequestDTO;
import com.example.backend.dto.request.PlannedPartialLeaveRequestDTO;
import com.example.backend.dto.respones.DriverLeaveBatchResponse;
import com.example.backend.dto.respones.DriverLeaveResponse;
import com.example.backend.dto.respones.DriverLeaveHistoryResponse;
import com.example.backend.dto.respones.DriverMonthlyLeaveSummaryResponse;
import com.example.backend.dto.respones.LeaveNotificationEvent;
import com.example.backend.entity.AttendanceRecordsEntity;
import com.example.backend.entity.DriverLeaveRequestsEntity;
import com.example.backend.entity.DriverLeaveRequestEventsEntity;
import com.example.backend.entity.DriverShiftsEntity;
import com.example.backend.entity.DriversEntity;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional
public class DriverLeaveRequestService {
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final Long SYSTEM_ACTOR_ID = 0L;
    private static final String SYSTEM_ACTOR_ACCOUNT = "SYSTEM";
    private static final String NO_SHOW_REASON =
            "表定上班日整班未打卡，系統先列為特殊事由，等待司機說明及主管審核";

    private final DriverLeaveRequestsDAO requestsDAO;
    private final DriverLeaveRequestEventsDAO eventsDAO;
    private final DriverShiftsDAO shiftsDAO;
    private final ScheduleMonthsDAO monthsDAO;
    private final DriversDAO driversDAO;
    private final AdminUsersDAO adminUsersDAO;
    private final AttendanceRecordsDAO attendanceDAO;
    private final DriverScheduleService scheduleService;
    private final ApplicationEventPublisher eventPublisher;

    public DriverLeaveRequestService(
            DriverLeaveRequestsDAO requestsDAO,
            DriverLeaveRequestEventsDAO eventsDAO,
            DriverShiftsDAO shiftsDAO,
            ScheduleMonthsDAO monthsDAO,
            DriversDAO driversDAO,
            AdminUsersDAO adminUsersDAO,
            AttendanceRecordsDAO attendanceDAO,
            DriverScheduleService scheduleService,
            ApplicationEventPublisher eventPublisher
    ) {
        this.requestsDAO = requestsDAO;
        this.eventsDAO = eventsDAO;
        this.shiftsDAO = shiftsDAO;
        this.monthsDAO = monthsDAO;
        this.driversDAO = driversDAO;
        this.adminUsersDAO = adminUsersDAO;
        this.attendanceDAO = attendanceDAO;
        this.scheduleService = scheduleService;
        this.eventPublisher = eventPublisher;
    }

    public DriverLeaveResponse submit(Long driverId, DriverLeaveRequestDTO dto) {
        DriversEntity driver = requireActiveDriver(driverId);
        if (dto == null || dto.getWorkDate() == null || dto.getLeaveType() == null) {
            throw new IllegalArgumentException("請假日期與假別不能為空");
        }
        LocalDate today = LocalDate.now(TAIPEI);
        if (dto.getWorkDate().isBefore(today)) {
            throw new IllegalArgumentException("不能申請過去日期的請假");
        }
        if (dto.getWorkDate().isAfter(today)) {
            throw new IllegalArgumentException("未來日期請使用預排請假功能");
        }
        if (dto.getLeaveType() == LeaveType.ABSENT) {
            throw new IllegalArgumentException("曠職只能由主管依出勤狀況認定");
        }

        DriverShiftsEntity shift = requirePublishedWorkingShift(driverId, dto.getWorkDate());
        LeavePeriod period = validatePeriod(shift, dto.getLeaveStart(), dto.getLeaveEnd());
        if (period.fullDay() && attendanceDAO.existsByDriverShiftId(shift.getId())) {
            throw new IllegalArgumentException("已打上班卡後只能申請部分時段假，請填寫開始與結束時間");
        }
        ensureNoConflict(driverId, dto.getWorkDate(), period);

        DriverLeaveRequestsEntity request = new DriverLeaveRequestsEntity();
        request.setDriverId(driverId);
        request.setDriverShiftId(shift.getId());
        request.setRequestMode(LeaveRequestMode.TEMPORARY);
        request.setWorkDate(dto.getWorkDate());
        request.setRequestedLeaveType(dto.getLeaveType());
        request.setLeaveType(dto.getLeaveType());
        request.setFullDay(period.fullDay());
        request.setLeaveStart(period.start());
        request.setLeaveEnd(period.end());
        request.setRequestReason(requireText(dto.getReason(), "請填寫請假原因"));
        request.setSubmissionSource(LeaveSubmissionSource.DRIVER);
        request.setDriverReadAt(LocalDateTime.now(TAIPEI));

        DriverLeaveRequestsEntity saved = requestsDAO.save(request);
        recordEvent(saved, LeaveRequestEventType.SUBMITTED, LeaveSubmissionSource.DRIVER,
                driverId, driver.getAccount(), null, LeaveRequestStatus.PENDING,
                null, saved.getLeaveType(), saved.getRequestReason());
        DriverLeaveResponse response = toResponse(saved);
        eventPublisher.publishEvent(LeaveNotificationEvent.admins(response));
        return response;
    }

    /**
     * 司機預先排假：一種假別可一次勾選多個未來上班日；不同假別各自形成一組。
     * 所有日期先完成驗證才寫入，避免同一批只有部分成功。
     */
    public List<DriverLeaveBatchResponse> submitPlannedBatches(
            Long driverId,
            DriverPlannedLeaveBatchRequestDTO dto
    ) {
        DriversEntity driver = requireActiveDriver(driverId);
        if (dto == null || dto.getGroups() == null || dto.getGroups().isEmpty()) {
            throw new IllegalArgumentException("至少要有一組預排請假");
        }

        LocalDate today = LocalDate.now(TAIPEI);
        Set<LocalDate> selectedDates = new HashSet<>();
        List<PreparedLeaveGroup> preparedGroups = new ArrayList<>();
        for (DriverPlannedLeaveBatchRequestDTO.Group group : dto.getGroups()) {
            if (group == null || group.getLeaveType() == null
                    || group.getWorkDates() == null || group.getWorkDates().isEmpty()) {
                throw new IllegalArgumentException("每組都必須選擇假別與至少一個日期");
            }
            if (group.getLeaveType() == LeaveType.ABSENT) {
                throw new IllegalArgumentException("曠職只能由主管依出勤狀況認定");
            }
            String reason = requireText(group.getReason(), "每組預排請假都必須填寫原因");
            List<LocalDate> dates = group.getWorkDates().stream().sorted().toList();
            List<DriverShiftsEntity> shifts = new ArrayList<>();
            for (LocalDate date : dates) {
                if (date == null || !date.isAfter(today)) {
                    throw new IllegalArgumentException("預排請假只能選擇明天以後的日期");
                }
                if (!selectedDates.add(date)) {
                    throw new IllegalArgumentException("同一日期不能重複放在不同預排請假組別");
                }
                DriverShiftsEntity shift = requirePublishedWorkingShift(driverId, date);
                ensureNoConflict(driverId, date, new LeavePeriod(true, null, null));
                shifts.add(shift);
            }
            preparedGroups.add(new PreparedLeaveGroup(
                    UUID.randomUUID().toString(), group.getLeaveType(), reason, shifts));
        }

        List<DriverLeaveBatchResponse> result = new ArrayList<>();
        for (PreparedLeaveGroup group : preparedGroups) {
            List<DriverLeaveRequestsEntity> savedItems = new ArrayList<>();
            for (DriverShiftsEntity shift : group.shifts()) {
                DriverLeaveRequestsEntity request = new DriverLeaveRequestsEntity();
                request.setBatchId(group.batchId());
                request.setRequestMode(LeaveRequestMode.PREPLANNED);
                request.setDriverId(driverId);
                request.setDriverShiftId(shift.getId());
                request.setWorkDate(shift.getWorkDate());
                request.setRequestedLeaveType(group.leaveType());
                request.setLeaveType(group.leaveType());
                request.setFullDay(true);
                request.setRequestReason(group.reason());
                request.setSubmissionSource(LeaveSubmissionSource.DRIVER);
                request.setDriverReadAt(LocalDateTime.now(TAIPEI));

                DriverLeaveRequestsEntity saved = requestsDAO.save(request);
                savedItems.add(saved);
                recordEvent(saved, LeaveRequestEventType.SUBMITTED, LeaveSubmissionSource.DRIVER,
                        driverId, driver.getAccount(), null, LeaveRequestStatus.PENDING,
                        null, saved.getLeaveType(), saved.getRequestReason());
                eventPublisher.publishEvent(LeaveNotificationEvent.admins(toResponse(saved)));
            }
            result.add(toBatchResponse(savedItems));
        }
        return result;
    }

    /**
     * 事後補請假只處理已結束、原本應上班且整天沒有任何打卡的日期。
     * 若系統已自動建立未到紀錄，就在同一筆紀錄補上司機說明，保留最初的系統稽核事件。
     */
    public DriverLeaveResponse submitMakeupLeave(Long driverId, DriverMakeupLeaveRequestDTO dto) {
        DriversEntity driver = requireActiveDriver(driverId);
        if (dto == null || dto.getWorkDate() == null || dto.getLeaveType() == null) {
            throw new IllegalArgumentException("補請假日期與假別不能為空");
        }
        if (!dto.getWorkDate().isBefore(LocalDate.now(TAIPEI))) {
            throw new IllegalArgumentException("事後補請假只能選擇今天以前的日期");
        }
        if (dto.getLeaveType() == LeaveType.ABSENT) {
            throw new IllegalArgumentException("曠職只能由主管依出勤狀況認定");
        }

        DriverShiftsEntity shift = requirePublishedWorkingShift(driverId, dto.getWorkDate());
        if (!isFinishedPublishedWorkShift(shift, LocalDateTime.now(TAIPEI))) {
            throw new IllegalArgumentException("該班次尚未結束，不能使用事後補請假");
        }
        if (attendanceDAO.existsByDriverShiftId(shift.getId())) {
            throw new IllegalArgumentException("該日期已有打卡紀錄，不屬於整天未到的事後補請假");
        }

        String reason = requireText(dto.getReason(), "請填寫補請假原因");
        String evidencePhotoUrl = normalizeEvidencePhotoUrl(dto.getEvidencePhotoUrl());
        List<DriverLeaveRequestsEntity> existing =
                requestsDAO.findForUpdateByDriverIdAndWorkDate(driverId, dto.getWorkDate());
        DriverLeaveRequestsEntity request = existing.stream()
                .filter(item -> item.getStatus() == LeaveRequestStatus.PENDING)
                .filter(item -> item.getSubmissionSource() == LeaveSubmissionSource.SYSTEM)
                .filter(item -> Boolean.TRUE.equals(item.getFullDay()))
                .findFirst()
                .orElse(null);

        if (request == null && existing.stream()
                .anyMatch(item -> item.getStatus() != LeaveRequestStatus.REJECTED)) {
            throw new IllegalArgumentException("該日期已有待審核或已核准的請假紀錄");
        }

        boolean updatingSystemRecord = request != null;
        LeaveType oldType = updatingSystemRecord ? request.getLeaveType() : null;
        if (request == null) {
            request = new DriverLeaveRequestsEntity();
            request.setDriverId(driverId);
            request.setDriverShiftId(shift.getId());
            request.setWorkDate(dto.getWorkDate());
            request.setFullDay(true);
            request.setSubmissionSource(LeaveSubmissionSource.DRIVER);
        }
        request.setRequestMode(LeaveRequestMode.MAKEUP);
        request.setRequestedLeaveType(dto.getLeaveType());
        request.setLeaveType(dto.getLeaveType());
        request.setRequestReason(reason);
        request.setEvidencePhotoUrl(evidencePhotoUrl);
        request.setDriverReadAt(LocalDateTime.now(TAIPEI));

        DriverLeaveRequestsEntity saved = requestsDAO.saveAndFlush(request);
        recordEvent(saved, LeaveRequestEventType.DRIVER_EXPLANATION_SUBMITTED,
                LeaveSubmissionSource.DRIVER, driverId, driver.getAccount(),
                updatingSystemRecord ? LeaveRequestStatus.PENDING : null,
                LeaveRequestStatus.PENDING, oldType, saved.getLeaveType(), saved.getRequestReason());
        DriverLeaveResponse response = toResponse(saved);
        eventPublisher.publishEvent(LeaveNotificationEvent.admins(response));
        return response;
    }

    @Transactional(readOnly = true)
    public List<DriverLeaveResponse> findMine(Long driverId, YearMonth month, boolean unreadOnly) {
        requireDriver(driverId);
        List<DriverLeaveRequestsEntity> requests = month == null
                ? requestsDAO.findByDriverIdOrderByRequestedAtDesc(driverId)
                : requestsDAO.findByDriverIdAndWorkDateBetweenOrderByWorkDateAscRequestedAtAsc(
                        driverId, month.atDay(1), month.atEndOfMonth());
        return requests.stream()
                .filter(request -> !unreadOnly || request.getDriverReadAt() == null)
                .map(this::toResponse)
                .toList();
    }

    public DriverLeaveResponse markRead(Long driverId, Long requestId) {
        DriverLeaveRequestsEntity request = requestsDAO.findForUpdate(requestId)
                .orElseThrow(() -> new EntityNotFoundException("找不到請假紀錄"));
        if (!driverId.equals(request.getDriverId())) {
            throw new IllegalArgumentException("不能讀取其他司機的請假紀錄");
        }
        request.setDriverReadAt(LocalDateTime.now(TAIPEI));
        return toResponse(requestsDAO.saveAndFlush(request));
    }

    @Transactional(readOnly = true)
    public List<DriverLeaveHistoryResponse> findMyHistory(Long driverId, Long requestId) {
        DriverLeaveRequestsEntity request = requestsDAO.findById(requestId)
                .orElseThrow(() -> new EntityNotFoundException("找不到請假紀錄"));
        if (!driverId.equals(request.getDriverId())) {
            throw new IllegalArgumentException("不能查詢其他司機的請假異動歷史");
        }
        return findHistoryRecords(requestId);
    }

    @Transactional(readOnly = true)
    public List<DriverLeaveResponse> findPending() {
        return requestsDAO.findByStatusOrderByRequestedAtAsc(LeaveRequestStatus.PENDING).stream()
                .map(this::toResponse)
                .toList();
    }

    /** 主管取得已依「同司機、同假別、多日期」打包的待審預排申請。 */
    @Transactional(readOnly = true)
    public List<DriverLeaveBatchResponse> findPendingBatches() {
        Map<String, List<DriverLeaveRequestsEntity>> grouped = requestsDAO
                .findByStatusOrderByRequestedAtAsc(LeaveRequestStatus.PENDING).stream()
                .filter(request -> request.getBatchId() != null)
                .collect(Collectors.groupingBy(
                        DriverLeaveRequestsEntity::getBatchId,
                        LinkedHashMap::new,
                        Collectors.toList()));
        return grouped.values().stream().map(this::toBatchResponse).toList();
    }

    @Transactional(readOnly = true)
    public DriverMonthlyLeaveSummaryResponse findMonthly(Long driverId, YearMonth month) {
        DriversEntity driver = requireDriver(driverId);
        if (month == null) {
            throw new IllegalArgumentException("請選擇查詢月份");
        }
        List<DriverLeaveResponse> records = requestsDAO
                .findByDriverIdAndWorkDateBetweenOrderByWorkDateAscRequestedAtAsc(
                        driverId, month.atDay(1), month.atEndOfMonth())
                .stream().map(this::toResponse).toList();
        return new DriverMonthlyLeaveSummaryResponse(
                driverId, driver.getName(), month, !records.isEmpty(),
                records.isEmpty() ? "無請假紀錄" : null, records);
    }

    @Transactional(readOnly = true)
    public List<DriverLeaveHistoryResponse> findHistory(Long requestId) {
        if (!requestsDAO.existsById(requestId)) {
            throw new EntityNotFoundException("找不到請假紀錄");
        }
        return findHistoryRecords(requestId);
    }

    public DriverLeaveResponse approve(
            Long requestId,
            LeaveType approvedType,
            String decisionReason,
            Long adminId,
            String adminAccount
    ) {
        requireAdmin(adminId);
        DriverLeaveRequestsEntity request = findPending(requestId);
        requireSingleRequest(request);
        return approvePendingRequest(request, approvedType, decisionReason, adminId, adminAccount);
    }

    public DriverLeaveBatchResponse approveBatch(
            String batchId,
            String decisionReason,
            Long adminId,
            String adminAccount
    ) {
        requireAdmin(adminId);
        List<DriverLeaveRequestsEntity> requests = findPendingBatch(batchId);
        String normalizedReason = requireText(decisionReason, "核准請假必須填寫原因");
        requests.forEach(request -> approvePendingRequest(
                request, null, normalizedReason, adminId, adminAccount));
        return toBatchResponse(requests);
    }

    private DriverLeaveResponse approvePendingRequest(
            DriverLeaveRequestsEntity request,
            LeaveType approvedType,
            String decisionReason,
            Long adminId,
            String adminAccount
    ) {
        DriverShiftsEntity shift = requireMatchingShift(request);
        LeaveRequestStatus oldStatus = request.getStatus();
        LeaveType oldType = request.getLeaveType();
        LeaveType finalType = approvedType == null ? request.getLeaveType() : approvedType;

        if (Boolean.TRUE.equals(request.getFullDay())
                && request.getRequestMode() == LeaveRequestMode.PREPLANNED
                && finalType != LeaveType.ABSENT) {
            scheduleService.markLeave(shift.getId(), request.getRequestReason(), shift.getVersion());
        } else if (shift.getShiftType() != ShiftType.WORK) {
            throw new IllegalArgumentException("部分時段請假必須保留為上班班次");
        }

        request.setLeaveType(finalType);
        request.setStatus(LeaveRequestStatus.APPROVED);
        request.setDecisionReason(requireText(decisionReason, "核准請假必須填寫原因"));
        applyReviewer(request, adminId, adminAccount);
        request.setDriverReadAt(null);
        DriverLeaveRequestsEntity saved = requestsDAO.saveAndFlush(request);
        recordEvent(saved, LeaveRequestEventType.APPROVED, LeaveSubmissionSource.ADMIN,
                adminId, adminAccount, oldStatus, saved.getStatus(), oldType,
                saved.getLeaveType(), saved.getDecisionReason());
        coverLateAbsenceIfApplicable(saved, shift);

        DriverLeaveResponse response = toResponse(saved);
        eventPublisher.publishEvent(LeaveNotificationEvent.driver(response));
        return response;
    }

    public DriverLeaveResponse reject(
            Long requestId,
            String decisionReason,
            Long adminId,
            String adminAccount
    ) {
        requireAdmin(adminId);
        DriverLeaveRequestsEntity request = findPending(requestId);
        requireSingleRequest(request);
        return rejectPendingRequest(request, decisionReason, adminId, adminAccount);
    }

    public DriverLeaveBatchResponse rejectBatch(
            String batchId,
            String decisionReason,
            Long adminId,
            String adminAccount
    ) {
        requireAdmin(adminId);
        List<DriverLeaveRequestsEntity> requests = findPendingBatch(batchId);
        String normalizedReason = requireText(decisionReason, "拒絕請假必須填寫原因");
        requests.forEach(request -> rejectPendingRequest(
                request, normalizedReason, adminId, adminAccount));
        return toBatchResponse(requests);
    }

    private DriverLeaveResponse rejectPendingRequest(
            DriverLeaveRequestsEntity request,
            String decisionReason,
            Long adminId,
            String adminAccount
    ) {
        LeaveRequestStatus oldStatus = request.getStatus();
        LeaveType oldType = request.getLeaveType();
        request.setStatus(LeaveRequestStatus.REJECTED);
        request.setDecisionReason(requireText(decisionReason, "拒絕請假必須填寫原因"));
        applyReviewer(request, adminId, adminAccount);
        request.setDriverReadAt(null);
        DriverLeaveRequestsEntity saved = requestsDAO.saveAndFlush(request);
        recordEvent(saved, LeaveRequestEventType.REJECTED, LeaveSubmissionSource.ADMIN,
                adminId, adminAccount, oldStatus, saved.getStatus(), oldType,
                saved.getLeaveType(), saved.getDecisionReason());
        DriverLeaveResponse response = toResponse(saved);
        eventPublisher.publishEvent(LeaveNotificationEvent.driver(response));
        return response;
    }

    public DriverLeaveResponse correctType(
            Long requestId,
            LeaveType leaveType,
            String reason,
            Long adminId,
            String adminAccount
    ) {
        requireAdmin(adminId);
        if (leaveType == null) {
            throw new IllegalArgumentException("請選擇修正後的假別");
        }
        DriverLeaveRequestsEntity request = requestsDAO.findForUpdate(requestId)
                .orElseThrow(() -> new EntityNotFoundException("找不到請假紀錄"));
        requireSingleRequest(request);
        if (request.getStatus() != LeaveRequestStatus.APPROVED) {
            throw new IllegalArgumentException("只有已核准的請假紀錄可以修正假別");
        }
        if (request.getLeaveType() == leaveType) {
            throw new IllegalArgumentException("修正後的假別與目前相同");
        }

        LeaveType oldType = request.getLeaveType();
        request.setLeaveType(leaveType);
        request.setTypeChangeReason(requireText(reason, "修正假別必須填寫原因"));
        request.setTypeChangedAt(LocalDateTime.now(TAIPEI));
        request.setTypeChangedByAdminId(adminId);
        request.setTypeChangedBy(normalizeAccount(adminAccount));
        request.setDriverReadAt(null);
        DriverLeaveRequestsEntity saved = requestsDAO.saveAndFlush(request);
        recordEvent(saved, LeaveRequestEventType.TYPE_CHANGED, LeaveSubmissionSource.ADMIN,
                adminId, adminAccount, saved.getStatus(), saved.getStatus(), oldType,
                saved.getLeaveType(), saved.getTypeChangeReason());
        DriverLeaveResponse response = toResponse(saved);
        eventPublisher.publishEvent(LeaveNotificationEvent.driver(response));
        return response;
    }

    public DriverLeaveBatchResponse correctBatchType(
            String batchId,
            LeaveType leaveType,
            String reason,
            Long adminId,
            String adminAccount
    ) {
        requireAdmin(adminId);
        if (leaveType == null || leaveType == LeaveType.ABSENT) {
            throw new IllegalArgumentException("請選擇可用於預排請假的修正假別");
        }
        String normalizedReason = requireText(reason, "修正假別必須填寫原因");
        List<DriverLeaveRequestsEntity> requests = requestsDAO.findBatchForUpdate(batchId);
        if (requests.isEmpty()) {
            throw new EntityNotFoundException("找不到預排請假群組");
        }
        for (DriverLeaveRequestsEntity request : requests) {
            if (request.getStatus() != LeaveRequestStatus.APPROVED) {
                throw new IllegalArgumentException("只有整組已核准的預排請假可以修正假別");
            }
            if (request.getLeaveType() == leaveType) {
                throw new IllegalArgumentException("修正後的假別與目前相同");
            }
        }

        for (DriverLeaveRequestsEntity request : requests) {
            LeaveType oldType = request.getLeaveType();
            request.setLeaveType(leaveType);
            request.setTypeChangeReason(normalizedReason);
            request.setTypeChangedAt(LocalDateTime.now(TAIPEI));
            request.setTypeChangedByAdminId(adminId);
            request.setTypeChangedBy(normalizeAccount(adminAccount));
            request.setDriverReadAt(null);
            DriverLeaveRequestsEntity saved = requestsDAO.saveAndFlush(request);
            recordEvent(saved, LeaveRequestEventType.TYPE_CHANGED, LeaveSubmissionSource.ADMIN,
                    adminId, adminAccount, saved.getStatus(), saved.getStatus(), oldType,
                    saved.getLeaveType(), saved.getTypeChangeReason());
            eventPublisher.publishEvent(LeaveNotificationEvent.driver(toResponse(saved)));
        }
        return toBatchResponse(requestsDAO.findByBatchIdOrderByWorkDateAsc(batchId));
    }

    /** 主管預排部分時段假；當天仍是 WORK，只附加放假時間。 */
    public DriverLeaveResponse createPlannedPartial(
            PlannedPartialLeaveRequestDTO dto,
            Long adminId,
            String adminAccount
    ) {
        requireAdmin(adminId);
        if (dto == null || dto.getDriverId() == null || dto.getWorkDate() == null
                || dto.getLeaveType() == null) {
            throw new IllegalArgumentException("預排假資料不完整");
        }
        if (dto.getLeaveType() == LeaveType.ABSENT) {
            throw new IllegalArgumentException("曠職不能作為預排假");
        }
        if (dto.getWorkDate().isBefore(LocalDate.now(TAIPEI))) {
            throw new IllegalArgumentException("不能新增過去日期的預排假");
        }
        requireActiveDriver(dto.getDriverId());
        DriverShiftsEntity shift = shiftsDAO.findForUpdateByDriverIdAndWorkDate(
                        dto.getDriverId(), dto.getWorkDate())
                .orElseThrow(() -> new IllegalArgumentException("該日期沒有班表"));
        if (shift.getShiftType() != ShiftType.WORK) {
            throw new IllegalArgumentException("只有上班日可以新增部分時段預排假");
        }
        LeavePeriod period = validatePeriod(shift, dto.getLeaveStart(), dto.getLeaveEnd());
        if (period.fullDay()) {
            throw new IllegalArgumentException("整天預排假請直接把班表改為請假");
        }
        ensureNoConflict(dto.getDriverId(), dto.getWorkDate(), period);

        LocalDateTime now = LocalDateTime.now(TAIPEI);
        DriverLeaveRequestsEntity request = new DriverLeaveRequestsEntity();
        request.setDriverId(dto.getDriverId());
        request.setDriverShiftId(shift.getId());
        request.setRequestMode(LeaveRequestMode.ADMIN_PLANNED_PARTIAL);
        request.setWorkDate(dto.getWorkDate());
        request.setRequestedLeaveType(dto.getLeaveType());
        request.setLeaveType(dto.getLeaveType());
        request.setFullDay(false);
        request.setLeaveStart(period.start());
        request.setLeaveEnd(period.end());
        request.setRequestReason(requireText(dto.getReason(), "請填寫預排假原因"));
        request.setSubmissionSource(LeaveSubmissionSource.ADMIN);
        request.setStatus(LeaveRequestStatus.APPROVED);
        request.setDecisionReason("主管預排部分時段假：" + request.getRequestReason());
        request.setReviewedAt(now);
        request.setReviewedByAdminId(adminId);
        request.setReviewedBy(normalizeAccount(adminAccount));
        request.setDriverReadAt(null);

        DriverLeaveRequestsEntity saved = requestsDAO.saveAndFlush(request);
        recordEvent(saved, LeaveRequestEventType.PLANNED_CREATED, LeaveSubmissionSource.ADMIN,
                adminId, adminAccount, null, LeaveRequestStatus.APPROVED, null,
                saved.getLeaveType(), saved.getRequestReason());
        DriverLeaveResponse response = toResponse(saved);
        eventPublisher.publishEvent(LeaveNotificationEvent.driver(response));
        return response;
    }

    /**
     * 表定上班日整班結束後仍完全沒有打卡紀錄時，先建立「特殊事由」待審紀錄。
     * 每分鐘檢查今天與昨天，兼顧跨午夜班次及服務短暫停機後的補建。
     */
    @Scheduled(cron = "0 * * * * *", zone = "Asia/Taipei")
    public void createNoShowRequests() {
        LocalDateTime now = LocalDateTime.now(TAIPEI);
        createNoShowRequestsForDate(now.toLocalDate(), now);
        createNoShowRequestsForDate(now.toLocalDate().minusDays(1), now);
    }

    void createNoShowRequestsForDate(LocalDate workDate, LocalDateTime now) {
        for (DriverShiftsEntity candidate : shiftsDAO.findAllByWorkDate(workDate)) {
            if (!isFinishedPublishedWorkShift(candidate, now)) {
                continue;
            }

            DriverShiftsEntity shift = shiftsDAO.findForUpdateByDriverIdAndWorkDate(
                            candidate.getDriverId(), candidate.getWorkDate())
                    .orElse(null);
            if (shift == null || shift.getShiftType() != ShiftType.WORK
                    || attendanceDAO.existsByDriverShiftId(shift.getId())
                    || requestsDAO.existsByDriverShiftIdAndSubmissionSource(
                            shift.getId(), LeaveSubmissionSource.SYSTEM)) {
                continue;
            }

            boolean alreadyHasFullDayRequest = requestsDAO
                    .findByDriverIdAndWorkDateOrderByRequestedAtAsc(
                            shift.getDriverId(), shift.getWorkDate())
                    .stream()
                    .anyMatch(request -> request.getStatus() != LeaveRequestStatus.REJECTED
                            && Boolean.TRUE.equals(request.getFullDay()));
            if (alreadyHasFullDayRequest) {
                continue;
            }

            DriverLeaveRequestsEntity request = new DriverLeaveRequestsEntity();
            request.setDriverId(shift.getDriverId());
            request.setDriverShiftId(shift.getId());
            request.setRequestMode(LeaveRequestMode.SYSTEM_NO_SHOW);
            request.setWorkDate(shift.getWorkDate());
            request.setRequestedLeaveType(LeaveType.SPECIAL);
            request.setLeaveType(LeaveType.SPECIAL);
            request.setFullDay(true);
            request.setRequestReason(NO_SHOW_REASON);
            request.setSubmissionSource(LeaveSubmissionSource.SYSTEM);
            request.setDriverReadAt(null);

            DriverLeaveRequestsEntity saved = requestsDAO.save(request);
            recordEvent(saved, LeaveRequestEventType.AUTO_NO_SHOW_CREATED,
                    LeaveSubmissionSource.SYSTEM, SYSTEM_ACTOR_ID, SYSTEM_ACTOR_ACCOUNT,
                    null, LeaveRequestStatus.PENDING, null, LeaveType.SPECIAL, NO_SHOW_REASON);
            DriverLeaveResponse response = toResponse(saved);
            eventPublisher.publishEvent(LeaveNotificationEvent.admins(response));
            eventPublisher.publishEvent(LeaveNotificationEvent.driver(response));
        }
    }

    private boolean isFinishedPublishedWorkShift(
            DriverShiftsEntity shift,
            LocalDateTime now
    ) {
        if (shift.getShiftType() != ShiftType.WORK
                || shift.getWorkStart() == null || shift.getWorkEnd() == null) {
            return false;
        }
        boolean published = monthsDAO.findById(shift.getScheduleMonthId())
                .map(month -> month.getStatus() == ScheduleStatus.PUBLISHED)
                .orElse(false);
        if (!published) {
            return false;
        }
        LocalDateTime end = shift.getWorkDate().atTime(shift.getWorkEnd());
        if (!shift.getWorkEnd().isAfter(shift.getWorkStart())) {
            end = end.plusDays(1);
        }
        return !now.isBefore(end);
    }

    private DriverLeaveRequestsEntity findPending(Long requestId) {
        DriverLeaveRequestsEntity request = requestsDAO.findForUpdate(requestId)
                .orElseThrow(() -> new EntityNotFoundException("找不到請假申請"));
        if (request.getStatus() != LeaveRequestStatus.PENDING) {
            throw new IllegalArgumentException("這筆請假申請已經審核");
        }
        return request;
    }

    private List<DriverLeaveRequestsEntity> findPendingBatch(String batchId) {
        if (batchId == null || batchId.isBlank()) {
            throw new IllegalArgumentException("預排請假群組編號不能為空");
        }
        List<DriverLeaveRequestsEntity> requests = requestsDAO.findBatchForUpdate(batchId);
        if (requests.isEmpty()) {
            throw new EntityNotFoundException("找不到預排請假群組");
        }
        if (requests.stream().anyMatch(request -> request.getStatus() != LeaveRequestStatus.PENDING)) {
            throw new IllegalArgumentException("這組預排請假已經審核，不能重複處理");
        }
        return requests;
    }

    private void requireSingleRequest(DriverLeaveRequestsEntity request) {
        if (request.getBatchId() != null) {
            throw new IllegalArgumentException("這是多日預排請假，必須使用群組審核功能");
        }
    }

    private DriverShiftsEntity requirePublishedWorkingShift(Long driverId, LocalDate workDate) {
        DriverShiftsEntity shift = shiftsDAO.findForUpdateByDriverIdAndWorkDate(driverId, workDate)
                .orElseThrow(() -> new IllegalArgumentException("該日期沒有班表，不能申請請假"));
        if (shift.getShiftType() != ShiftType.WORK) {
            throw new IllegalArgumentException("該日期不是上班日，不需要申請請假");
        }
        if (monthsDAO.findById(shift.getScheduleMonthId())
                .map(month -> month.getStatus() != ScheduleStatus.PUBLISHED)
                .orElse(true)) {
            throw new IllegalArgumentException("班表尚未發布，不能申請請假");
        }
        return shift;
    }

    private DriverShiftsEntity requireMatchingShift(DriverLeaveRequestsEntity request) {
        DriverShiftsEntity shift = shiftsDAO.findById(request.getDriverShiftId())
                .orElseThrow(() -> new EntityNotFoundException("請假單對應的班次已不存在"));
        if (!request.getDriverId().equals(shift.getDriverId())
                || !request.getWorkDate().equals(shift.getWorkDate())) {
            throw new IllegalArgumentException("請假單與班表資料不一致");
        }
        return shift;
    }

    private LeavePeriod validatePeriod(
            DriverShiftsEntity shift,
            LocalTime leaveStart,
            LocalTime leaveEnd
    ) {
        if (leaveStart == null && leaveEnd == null) {
            return new LeavePeriod(true, null, null);
        }
        if (leaveStart == null || leaveEnd == null) {
            throw new IllegalArgumentException("部分時段請假必須同時填寫開始與結束時間");
        }
        if (!leaveEnd.isAfter(leaveStart)) {
            throw new IllegalArgumentException("請假結束時間必須晚於開始時間");
        }
        if (shift.getWorkStart() != null && leaveStart.isBefore(shift.getWorkStart())) {
            throw new IllegalArgumentException("請假開始時間不能早於表定上班時間");
        }
        if (shift.getWorkEnd() != null && leaveEnd.isAfter(shift.getWorkEnd())) {
            throw new IllegalArgumentException("請假結束時間不能晚於表定下班時間");
        }
        return new LeavePeriod(false, leaveStart, leaveEnd);
    }

    private void ensureNoConflict(Long driverId, LocalDate date, LeavePeriod candidate) {
        boolean conflict = requestsDAO.findByDriverIdAndWorkDateOrderByRequestedAtAsc(driverId, date)
                .stream()
                .filter(existing -> existing.getStatus() != LeaveRequestStatus.REJECTED)
                .anyMatch(existing -> overlaps(existing, candidate));
        if (conflict) {
            throw new IllegalArgumentException("該日期已有待審核或已核准且時間重疊的請假紀錄");
        }
    }

    private boolean overlaps(DriverLeaveRequestsEntity existing, LeavePeriod candidate) {
        if (Boolean.TRUE.equals(existing.getFullDay()) || candidate.fullDay()) {
            return true;
        }
        return existing.getLeaveStart().isBefore(candidate.end())
                && candidate.start().isBefore(existing.getLeaveEnd());
    }

    private void coverLateAbsenceIfApplicable(
            DriverLeaveRequestsEntity request,
            DriverShiftsEntity shift
    ) {
        if (Boolean.TRUE.equals(request.getFullDay()) || shift.getWorkStart() == null) {
            return;
        }
        AttendanceRecordsEntity attendance = attendanceDAO
                .findForUpdate(request.getDriverId(), request.getWorkDate())
                .orElse(null);
        if (attendance == null || !Boolean.TRUE.equals(attendance.getLeaveRequired())) {
            return;
        }
        // 前端 time input 通常只有分鐘精度；8:30:01 打卡會算 31 分鐘，填到 8:31 即視為完整涵蓋。
        LocalTime clockInTime = attendance.getClockInAt().toLocalTime().truncatedTo(ChronoUnit.MINUTES);
        if (!request.getLeaveStart().isAfter(shift.getWorkStart())
                && !request.getLeaveEnd().isBefore(clockInTime)) {
            attendance.setLeaveRequired(false);
            attendance.setCoveredLeaveRequestId(request.getId());
            attendance.setPunctualityStatus(AttendancePunctualityStatus.LEAVE_COVERED);
            attendanceDAO.save(attendance);
        }
    }

    private DriversEntity requireDriver(Long driverId) {
        return driversDAO.findById(driverId)
                .orElseThrow(() -> new EntityNotFoundException("找不到司機，ID：" + driverId));
    }

    private DriversEntity requireActiveDriver(Long driverId) {
        DriversEntity driver = requireDriver(driverId);
        if (!Boolean.TRUE.equals(driver.getIsActive())) {
            throw new IllegalArgumentException("司機帳號已停用，不能申請請假");
        }
        return driver;
    }

    private void requireAdmin(Long adminId) {
        if (adminId == null || !adminUsersDAO.existsById(adminId)) {
            throw new EntityNotFoundException("找不到登入中的主管帳號");
        }
    }

    private void applyReviewer(DriverLeaveRequestsEntity request, Long adminId, String adminAccount) {
        request.setReviewedAt(LocalDateTime.now(TAIPEI));
        request.setReviewedByAdminId(adminId);
        request.setReviewedBy(normalizeAccount(adminAccount));
    }

    private void recordEvent(
            DriverLeaveRequestsEntity request,
            LeaveRequestEventType eventType,
            LeaveSubmissionSource actorType,
            Long actorId,
            String actorAccount,
            LeaveRequestStatus oldStatus,
            LeaveRequestStatus newStatus,
            LeaveType oldLeaveType,
            LeaveType newLeaveType,
            String reason
    ) {
        DriverLeaveRequestEventsEntity event = new DriverLeaveRequestEventsEntity();
        event.setLeaveRequestId(request.getId());
        event.setDriverId(request.getDriverId());
        event.setEventType(eventType);
        event.setActorType(actorType);
        event.setActorId(actorId);
        String normalizedAccount = normalizeAccount(actorAccount);
        event.setActorAccount(normalizedAccount == null
                ? actorType.name() + "#" + actorId : normalizedAccount);
        event.setOldStatus(oldStatus);
        event.setNewStatus(newStatus);
        event.setOldLeaveType(oldLeaveType);
        event.setNewLeaveType(newLeaveType);
        event.setReason(requireText(reason, "異動原因不能為空"));
        event.setOccurredAt(LocalDateTime.now(TAIPEI));
        eventsDAO.save(event);
    }

    private List<DriverLeaveHistoryResponse> findHistoryRecords(Long requestId) {
        return eventsDAO.findByLeaveRequestIdOrderByOccurredAtAscIdAsc(requestId).stream()
                .map(event -> new DriverLeaveHistoryResponse(
                        event.getId(), event.getLeaveRequestId(), event.getDriverId(),
                        event.getEventType(), event.getActorType(), event.getActorId(),
                        event.getActorAccount(), event.getOldStatus(), event.getNewStatus(),
                        event.getOldLeaveType(), event.getNewLeaveType(), event.getReason(),
                        event.getOccurredAt()))
                .toList();
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        String normalized = value.trim();
        if (normalized.length() > 500) {
            throw new IllegalArgumentException("原因不能超過 500 字");
        }
        return normalized;
    }

    private String normalizeAccount(String account) {
        if (account == null || account.isBlank()) {
            return null;
        }
        return account.length() <= 60 ? account : account.substring(0, 60);
    }

    private String normalizeEvidencePhotoUrl(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String normalized = url.trim();
        if (!normalized.matches("^/uploads/leave-evidence/[0-9a-fA-F-]{36}\\.(jpg|png|webp)$")) {
            throw new IllegalArgumentException("請假佐證照片網址不合法，請先使用佐證照片上傳 API");
        }
        return normalized;
    }

    private DriverLeaveResponse toResponse(DriverLeaveRequestsEntity request) {
        String driverName = driversDAO.findById(request.getDriverId())
                .map(DriversEntity::getName).orElse(null);
        return new DriverLeaveResponse(
                request.getId(), request.getBatchId(), request.getRequestMode(),
                request.getDriverId(), driverName,
                request.getDriverShiftId(),
                request.getWorkDate(), request.getRequestedLeaveType(), request.getLeaveType(),
                Boolean.TRUE.equals(request.getFullDay()), request.getLeaveStart(), request.getLeaveEnd(),
                request.getRequestReason(), request.getEvidencePhotoUrl(),
                request.getStatus(), request.getSubmissionSource(),
                request.getDecisionReason(), request.getRequestedAt(), request.getReviewedByAdminId(),
                request.getReviewedBy(), request.getReviewedAt(), request.getTypeChangeReason(),
                request.getTypeChangedAt(), request.getTypeChangedBy(), request.getDriverReadAt(),
                request.getLastUpdatedAt());
    }

    private DriverLeaveBatchResponse toBatchResponse(List<DriverLeaveRequestsEntity> requests) {
        if (requests == null || requests.isEmpty()) {
            throw new IllegalArgumentException("預排請假群組不能為空");
        }
        List<DriverLeaveRequestsEntity> sorted = requests.stream()
                .sorted((left, right) -> left.getWorkDate().compareTo(right.getWorkDate()))
                .toList();
        DriverLeaveRequestsEntity first = sorted.getFirst();
        List<DriverLeaveResponse> items = sorted.stream().map(this::toResponse).toList();
        String driverName = items.getFirst().driverName();
        return new DriverLeaveBatchResponse(
                first.getBatchId(), first.getDriverId(), driverName, first.getLeaveType(),
                sorted.stream().map(DriverLeaveRequestsEntity::getWorkDate).toList(),
                first.getRequestReason(), first.getStatus(), first.getDecisionReason(),
                first.getRequestedAt(), first.getReviewedBy(), first.getReviewedAt(), items);
    }

    private record LeavePeriod(boolean fullDay, LocalTime start, LocalTime end) {
    }

    private record PreparedLeaveGroup(
            String batchId,
            LeaveType leaveType,
            String reason,
            List<DriverShiftsEntity> shifts
    ) {
    }
}
