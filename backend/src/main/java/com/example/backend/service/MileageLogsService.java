package com.example.backend.service;

import com.example.backend.constants.RouteStatus;
import com.example.backend.dao.DriversDAO;
import com.example.backend.dao.MileageLogsDAO;
import com.example.backend.dao.RoutesDAO;
import com.example.backend.dto.request.MileageRequestDTO;
import com.example.backend.dto.respones.MileageLogResponse;
import com.example.backend.entity.DriversEntity;
import com.example.backend.entity.MileageLogsEntity;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

@Service
@Transactional
public class MileageLogsService {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    private final MileageLogsDAO mileageLogsDAO;
    private final DriversDAO driversDAO;
    private final RoutesDAO routesDAO;
    private final AttendanceService attendanceService;

    public MileageLogsService(
            MileageLogsDAO mileageLogsDAO,
            DriversDAO driversDAO,
            RoutesDAO routesDAO,
            AttendanceService attendanceService
    ) {
        this.mileageLogsDAO = mileageLogsDAO;
        this.driversDAO = driversDAO;
        this.routesDAO = routesDAO;
        this.attendanceService = attendanceService;
    }

    public MileageLogResponse start(Long driverId, MileageRequestDTO request) {
        LocalDateTime now = LocalDateTime.now(TAIPEI);
        LocalDate today = now.toLocalDate();
        requireActiveDriver(driverId);

        if (!routesDAO.existsByDateAndDriverIdAndStatus(
                today, driverId, RouteStatus.PUBLISHED)) {
            throw new IllegalArgumentException("今天沒有已發布的配送任務，不能登記出車里程");
        }
        if (!attendanceService.isGpsUploadAllowed(driverId)) {
            throw new IllegalArgumentException("請先完成上班打卡，且不可在休息或下班狀態開始出車");
        }
        if (mileageLogsDAO.findForUpdate(driverId, today).isPresent()) {
            throw new IllegalArgumentException("今天已經登記過出車里程");
        }

        MileageLogsEntity mileage = new MileageLogsEntity();
        mileage.setDriverId(driverId);
        mileage.setDate(today);
        mileage.setStartOdometer(request.getOdometer());
        mileage.setStartTime(now);
        return toResponse(mileageLogsDAO.save(mileage));
    }

    public MileageLogResponse end(Long driverId, MileageRequestDTO request) {
        LocalDateTime now = LocalDateTime.now(TAIPEI);
        MileageLogsEntity mileage = mileageLogsDAO.findForUpdate(driverId, now.toLocalDate())
                .orElseThrow(() -> new IllegalArgumentException("今天尚未登記出車里程"));

        if (mileage.getEndTime() != null || mileage.getEndOdometer() != null) {
            throw new IllegalArgumentException("今天已經登記過收車里程");
        }
        if (mileage.getStartOdometer() == null || mileage.getStartTime() == null) {
            throw new IllegalStateException("里程紀錄缺少出車資料，無法登記收車里程");
        }
        if (request.getOdometer() < mileage.getStartOdometer()) {
            throw new IllegalArgumentException("收車里程不能小於出車里程");
        }

        mileage.setEndOdometer(request.getOdometer());
        mileage.setEndTime(now);
        return toResponse(mileageLogsDAO.save(mileage));
    }

    private DriversEntity requireActiveDriver(Long driverId) {
        DriversEntity driver = driversDAO.findById(driverId)
                .orElseThrow(() -> new EntityNotFoundException("找不到司機，ID：" + driverId));
        if (!Boolean.TRUE.equals(driver.getIsActive())) {
            throw new IllegalArgumentException("司機帳號目前未啟用");
        }
        return driver;
    }

    private MileageLogResponse toResponse(MileageLogsEntity mileage) {
        Integer actualDistance = null;
        Long actualDurationMinutes = null;
        if (mileage.getStartOdometer() != null && mileage.getEndOdometer() != null) {
            actualDistance = mileage.getEndOdometer() - mileage.getStartOdometer();
        }
        if (mileage.getStartTime() != null && mileage.getEndTime() != null) {
            actualDurationMinutes = Duration.between(
                    mileage.getStartTime(), mileage.getEndTime()).toMinutes();
        }
        return new MileageLogResponse(
                mileage.getId(),
                mileage.getDriverId(),
                mileage.getDate(),
                mileage.getStartOdometer(),
                mileage.getEndOdometer(),
                mileage.getStartTime(),
                mileage.getEndTime(),
                actualDistance,
                actualDurationMinutes
        );
    }
}
