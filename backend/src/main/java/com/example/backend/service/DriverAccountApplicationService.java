package com.example.backend.service;

import com.example.backend.constants.DriverApplicationStatus;
import com.example.backend.dao.DriverAccountApplicationsDAO;
import com.example.backend.dao.DriversDAO;
import com.example.backend.dto.request.DriverAccountApplicationRequest;
import com.example.backend.dto.respones.DriverAccountApplicationResponse;
import com.example.backend.entity.DriverAccountApplicationsEntity;
import com.example.backend.entity.DriversEntity;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;

/** 司機帳號申請、主管待辦與核准流程。 */
@Service
@Transactional
public class DriverAccountApplicationService {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final LocalTime DEFAULT_WORK_START = LocalTime.of(8, 30);
    private static final LocalTime DEFAULT_WORK_END = LocalTime.of(17, 30);
    private static final int DEFAULT_REST_MINUTES = 90;
    private static final int DEFAULT_MAX_OVERTIME_MINUTES = 120;

    private static final int[] NATIONAL_ID_LETTER_CODES = {
            10, 11, 12, 13, 14, 15, 16, 17, 34,
            18, 19, 20, 21, 22, 35, 23, 24, 25,
            26, 27, 28, 29, 32, 30, 31, 33
    };

    private final DriverAccountApplicationsDAO applicationsDAO;
    private final DriversDAO driversDAO;
    private final PasswordEncoder passwordEncoder;
    private final DriverScheduleService driverScheduleService;

    public DriverAccountApplicationService(
            DriverAccountApplicationsDAO applicationsDAO,
            DriversDAO driversDAO,
            PasswordEncoder passwordEncoder,
            DriverScheduleService driverScheduleService
    ) {
        this.applicationsDAO = applicationsDAO;
        this.driversDAO = driversDAO;
        this.passwordEncoder = passwordEncoder;
        this.driverScheduleService = driverScheduleService;
    }

    /** 司機本人送件；遭拒後可使用相同帳號重新送件。 */
    public DriverAccountApplicationResponse submit(DriverAccountApplicationRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("申請資料不能為空");
        }

        String account = normalizeRequired(request.getAccount(), "登入帳號不能為空");
        String name = normalizeRequired(request.getName(), "司機姓名不能為空");
        String phone = normalizeRequired(request.getPhone(), "手機號碼不能為空");
        String nationalId = normalizeNationalId(request.getNationalId());
        requireValidNationalId(nationalId);

        if (account.length() > 50) {
            throw new IllegalArgumentException("登入帳號不能超過 50 字元");
        }
        if (name.length() > 30) {
            throw new IllegalArgumentException("司機姓名不能超過 30 字元");
        }
        if (!phone.matches("09\\d{8}")) {
            throw new IllegalArgumentException("手機號碼必須是 09 開頭的 10 位數字");
        }
        if (driversDAO.existsByAccountIgnoreCase(account)) {
            throw new IllegalArgumentException("司機帳號已經有人使用：" + account);
        }

        LocalDateTime now = LocalDateTime.now(TAIPEI);
        DriverAccountApplicationsEntity application = applicationsDAO
                .findByAccountIgnoreCase(account)
                .orElseGet(DriverAccountApplicationsEntity::new);

        if (application.getId() != null
                && application.getStatus() == DriverApplicationStatus.PENDING) {
            throw new IllegalArgumentException("這個帳號已有待主管核准的申請");
        }
        if (application.getId() != null
                && application.getStatus() == DriverApplicationStatus.APPROVED) {
            throw new IllegalArgumentException("這個帳號已經核准，請直接登入");
        }

        application.setAccount(account);
        application.setName(name);
        application.setPhone(phone);
        application.setPasswordHash(passwordEncoder.encode(nationalId));
        application.setNationalIdMasked(maskNationalId(nationalId));
        application.setStatus(DriverApplicationStatus.PENDING);
        application.setAppliedAt(now);
        application.setReviewedBy(null);
        application.setReviewedAt(null);
        application.setRejectionReason(null);
        application.setApprovedDriverId(null);
        return toResponse(applicationsDAO.saveAndFlush(application));
    }

    @Transactional(readOnly = true)
    public long countPending() {
        return applicationsDAO.countByStatus(DriverApplicationStatus.PENDING);
    }

    @Transactional(readOnly = true)
    public List<DriverAccountApplicationResponse> findPending() {
        return applicationsDAO
                .findByStatusOrderByAppliedAtAsc(DriverApplicationStatus.PENDING)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    /** 核准後建立正式司機帳號，並從明天開始同步既有班表。 */
    public DriverAccountApplicationResponse approve(Long applicationId, String reviewedBy) {
        DriverAccountApplicationsEntity application = findPendingForUpdate(applicationId);
        String reviewer = normalizeReviewer(reviewedBy);

        if (driversDAO.existsByAccountIgnoreCase(application.getAccount())) {
            throw new IllegalArgumentException("司機帳號已經有人使用：" + application.getAccount());
        }

        DriversEntity driver = new DriversEntity();
        driver.setAccount(application.getAccount());
        driver.setPassword(application.getPasswordHash());
        driver.setName(application.getName());
        driver.setPhone(application.getPhone());
        driver.setWorkStart(DEFAULT_WORK_START);
        driver.setWorkEnd(DEFAULT_WORK_END);
        driver.setRestDuration(DEFAULT_REST_MINUTES);
        driver.setMaxOvertimeMinutes(DEFAULT_MAX_OVERTIME_MINUTES);
        driver.setIsActive(true);
        driver = driversDAO.saveAndFlush(driver);

        driverScheduleService.synchronizeDriverAvailability(
                driver, LocalDate.now(TAIPEI).plusDays(1));

        application.setStatus(DriverApplicationStatus.APPROVED);
        application.setReviewedBy(reviewer);
        application.setReviewedAt(LocalDateTime.now(TAIPEI));
        application.setApprovedDriverId(driver.getId());
        application.setRejectionReason(null);
        return toResponse(applicationsDAO.save(application));
    }

    /** 主管拒絕後，申請人仍可使用相同帳號重新送件。 */
    public DriverAccountApplicationResponse reject(
            Long applicationId,
            String reviewedBy,
            String reason
    ) {
        DriverAccountApplicationsEntity application = findPendingForUpdate(applicationId);
        String normalizedReason = normalizeRequired(reason, "拒絕原因不能為空");
        if (normalizedReason.length() > 500) {
            throw new IllegalArgumentException("拒絕原因不能超過 500 字元");
        }

        application.setStatus(DriverApplicationStatus.REJECTED);
        application.setReviewedBy(normalizeReviewer(reviewedBy));
        application.setReviewedAt(LocalDateTime.now(TAIPEI));
        application.setRejectionReason(normalizedReason);
        application.setApprovedDriverId(null);
        return toResponse(applicationsDAO.save(application));
    }

    private DriverAccountApplicationsEntity findPendingForUpdate(Long applicationId) {
        if (applicationId == null || applicationId <= 0) {
            throw new IllegalArgumentException("申請 ID 必須大於 0");
        }
        DriverAccountApplicationsEntity application = applicationsDAO.findForUpdate(applicationId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "找不到司機帳號申請，ID：" + applicationId));
        if (application.getStatus() != DriverApplicationStatus.PENDING) {
            throw new IllegalArgumentException("這筆申請已經審核完成");
        }
        return application;
    }

    private String normalizeNationalId(String nationalId) {
        return normalizeRequired(nationalId, "身分證字號不能為空")
                .toUpperCase(Locale.ROOT);
    }

    private void requireValidNationalId(String nationalId) {
        if (!nationalId.matches("[A-Z][12]\\d{8}")) {
            throw new IllegalArgumentException("身分證字號格式不正確");
        }

        int letterCode = NATIONAL_ID_LETTER_CODES[nationalId.charAt(0) - 'A'];
        int sum = letterCode / 10 + (letterCode % 10) * 9;
        for (int index = 1; index <= 8; index++) {
            int digit = nationalId.charAt(index) - '0';
            sum += digit * (9 - index);
        }
        sum += nationalId.charAt(9) - '0';
        if (sum % 10 != 0) {
            throw new IllegalArgumentException("身分證字號檢查碼不正確");
        }
    }

    private String maskNationalId(String nationalId) {
        return nationalId.substring(0, 2) + "******" + nationalId.substring(8);
    }

    private String normalizeReviewer(String reviewedBy) {
        String reviewer = normalizeRequired(reviewedBy, "主管識別資料不能為空");
        if (reviewer.length() > 50) {
            throw new IllegalArgumentException("主管識別資料不能超過 50 字元");
        }
        return reviewer;
    }

    private String normalizeRequired(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private DriverAccountApplicationResponse toResponse(
            DriverAccountApplicationsEntity application
    ) {
        return new DriverAccountApplicationResponse(
                application.getId(),
                application.getAccount(),
                application.getName(),
                application.getPhone(),
                application.getNationalIdMasked(),
                application.getStatus(),
                application.getAppliedAt(),
                application.getReviewedBy(),
                application.getReviewedAt(),
                application.getRejectionReason(),
                application.getApprovedDriverId());
    }
}
