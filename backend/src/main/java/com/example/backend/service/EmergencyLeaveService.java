package com.example.backend.service;

import com.example.backend.constants.AttendanceStatus;
import com.example.backend.constants.EmergencyLeaveStatus;
import com.example.backend.constants.OrderStatus;
import com.example.backend.constants.RouteStatus;
import com.example.backend.constants.ShiftType;
import com.example.backend.dao.AttendanceRecordsDAO;
import com.example.backend.dao.DriversDAO;
import com.example.backend.dao.EmergencyLeaveRequestsDAO;
import com.example.backend.dao.OrdersDAO;
import com.example.backend.dao.RoutesDAO;
import com.example.backend.dao.VehiclesDAO;
import com.example.backend.dto.request.AttendanceRecordDTO;
import com.example.backend.dto.respones.EmergencyLeaveReplacementCandidateResponse;
import com.example.backend.dto.respones.EmergencyLeaveResponse;
import com.example.backend.entity.AttendanceRecordsEntity;
import com.example.backend.entity.DriversEntity;
import com.example.backend.entity.EmergencyLeaveRequestsEntity;
import com.example.backend.entity.OrdersEntity;
import com.example.backend.entity.RoutesEntity;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/** 上班途中臨時請假、主管審核與同日路線交接。 */
@Service
@Transactional
public class EmergencyLeaveService {
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    private final EmergencyLeaveRequestsDAO requestsDAO;
    private final AttendanceRecordsDAO attendanceRecordsDAO;
    private final DriversDAO driversDAO;
    private final RoutesDAO routesDAO;
    private final OrdersDAO ordersDAO;
    private final VehiclesDAO vehiclesDAO;
    private final AttendanceService attendanceService;
    private final DriverScheduleService driverScheduleService;

    public EmergencyLeaveService(
            EmergencyLeaveRequestsDAO requestsDAO,
            AttendanceRecordsDAO attendanceRecordsDAO,
            DriversDAO driversDAO,
            RoutesDAO routesDAO,
            OrdersDAO ordersDAO,
            VehiclesDAO vehiclesDAO,
            AttendanceService attendanceService,
            DriverScheduleService driverScheduleService
    ) {
        this.requestsDAO = requestsDAO;
        this.attendanceRecordsDAO = attendanceRecordsDAO;
        this.driversDAO = driversDAO;
        this.routesDAO = routesDAO;
        this.ordersDAO = ordersDAO;
        this.vehiclesDAO = vehiclesDAO;
        this.attendanceService = attendanceService;
        this.driverScheduleService = driverScheduleService;
    }

    public EmergencyLeaveResponse submit(Long driverId, String reason) {
        LocalDate today = LocalDate.now(TAIPEI);
        requireActiveDriver(driverId);
        String normalizedReason = requireText(reason, "請填寫臨時請假原因");

        // 鎖住當日出勤紀錄，避免同一位司機同時送出兩張待審申請。
        AttendanceRecordsEntity attendance = attendanceRecordsDAO.findForUpdate(driverId, today)
                .orElseThrow(() -> new IllegalArgumentException("尚未打上班卡，不能申請上班中臨時請假"));
        AttendanceRecordDTO current = attendanceService.findToday(driverId).orElseThrow();
        if (!isWorking(current.getStatus())) {
            throw new IllegalArgumentException("只有工作中或加班中的司機可以申請臨時請假");
        }
        if (requestsDAO.existsByDriverIdAndWorkDateAndStatus(
                driverId, today, EmergencyLeaveStatus.PENDING)) {
            throw new IllegalArgumentException("今天已有待主管審核的臨時請假申請");
        }

        List<RoutesEntity> routes = routesDAO.findByDateAndDriverIdAndStatusOrderByIdAsc(
                today, driverId, RouteStatus.PUBLISHED);
        if (routes.size() != 1) {
            throw new IllegalArgumentException("今天必須恰有一條已發布路線，才能申請路線交接");
        }

        RoutesEntity route = routes.getFirst();
        EmergencyLeaveRequestsEntity request = new EmergencyLeaveRequestsEntity();
        request.setDriverId(driverId);
        request.setWorkDate(today);
        request.setAttendanceRecordId(attendance.getId());
        request.setRouteId(route.getId());
        request.setVehicleId(route.getVehicleId());
        request.setReason(normalizedReason);
        return toResponse(requestsDAO.save(request));
    }

    @Transactional(readOnly = true)
    public List<EmergencyLeaveResponse> findMine(Long driverId) {
        return requestsDAO.findByDriverIdOrderByRequestedAtDesc(driverId).stream()
                .map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<EmergencyLeaveResponse> findPending() {
        return requestsDAO.findByStatusOrderByRequestedAtAsc(EmergencyLeaveStatus.PENDING).stream()
                .map(this::toResponse).toList();
    }

    public List<EmergencyLeaveReplacementCandidateResponse> replacementCandidates(Long requestId) {
        EmergencyLeaveRequestsEntity request = findPendingRequest(requestId);
        LocalDate today = LocalDate.now(TAIPEI);
        if (!today.equals(request.getWorkDate())) {
            return List.of();
        }
        return driversDAO.findAllByIsActiveTrueOrderByIdAsc().stream()
                .filter(driver -> !driver.getId().equals(request.getDriverId()))
                .filter(driver -> canTakeOver(driver.getId(), today))
                .map(driver -> new EmergencyLeaveReplacementCandidateResponse(
                        driver.getId(), driver.getAccount(), driver.getName(),
                        attendanceService.findToday(driver.getId())
                                .map(AttendanceRecordDTO::getStatus).orElse(null), true))
                .toList();
    }

    /** 核准、交接未完成訂單及路線、原司機下班卡都在同一交易中完成。 */
    public EmergencyLeaveResponse approve(Long requestId, Long replacementDriverId, String reviewedBy) {
        EmergencyLeaveRequestsEntity request = findPendingRequestForUpdate(requestId);
        LocalDate today = LocalDate.now(TAIPEI);
        if (!today.equals(request.getWorkDate())) {
            throw new IllegalArgumentException("只能在申請當天核准並交接路線");
        }
        if (replacementDriverId == null || replacementDriverId.equals(request.getDriverId())) {
            throw new IllegalArgumentException("請選擇另一位接手司機");
        }
        requireActiveDriver(replacementDriverId);
        // 不同請假單可能同時挑同一位接手者，先鎖住他的當日出勤紀錄再驗證。
        attendanceRecordsDAO.findForUpdate(replacementDriverId, today)
                .orElseThrow(() -> new IllegalArgumentException("接手司機尚未上班打卡"));
        if (!canTakeOver(replacementDriverId, today)) {
            throw new IllegalArgumentException("接手司機須有已發布上班班次、已打卡且尚未被指派路線");
        }

        RoutesEntity route = routesDAO.findForUpdate(request.getRouteId())
                .orElseThrow(() -> new EntityNotFoundException("找不到臨時請假的路線"));
        if (route.getStatus() != RouteStatus.PUBLISHED
                || !today.equals(route.getDate())
                || !request.getDriverId().equals(route.getDriverId())
                || !request.getVehicleId().equals(route.getVehicleId())) {
            throw new IllegalArgumentException("路線已異動，請重新確認交接內容");
        }

        List<OrdersEntity> orders = ordersDAO.findByRouteIdForUpdate(route.getId());
        int transferred = 0;
        for (OrdersEntity order : orders) {
            if (order.getStatus() == OrderStatus.CONFIRMED
                    || order.getStatus() == OrderStatus.IN_DELIVERY) {
                order.setAssignedDriverId(replacementDriverId);
                transferred++;
            }
        }
        if (transferred == 0) {
            throw new IllegalArgumentException("路線已沒有待配送訂單，無須安排接手司機");
        }
        ordersDAO.saveAll(orders);
        route.setDriverId(replacementDriverId);
        route.setVersion(route.getVersion() + 1);
        routesDAO.save(route);

        AttendanceRecordDTO attendance = attendanceService.findToday(request.getDriverId())
                .orElseThrow(() -> new IllegalArgumentException("原司機的出勤紀錄已遺失"));
        if (!request.getAttendanceRecordId().equals(attendance.getId())) {
            throw new IllegalArgumentException("原司機出勤紀錄已異動");
        }
        if (attendance.getClockOutAt() != null) {
            throw new IllegalArgumentException("原司機已經下班，不能再核准路線交接");
        }

        LocalDateTime now = LocalDateTime.now(TAIPEI);
        request.setStatus(EmergencyLeaveStatus.APPROVED);
        request.setReplacementDriverId(replacementDriverId);
        request.setTransferredOrderCount(transferred);
        request.setReviewedBy(reviewedBy);
        request.setReviewedAt(now);
        request.setRouteReassignedAt(now);
        return toResponse(requestsDAO.saveAndFlush(request));
    }

    public EmergencyLeaveResponse reject(Long requestId, String reason, String reviewedBy) {
        EmergencyLeaveRequestsEntity request = findPendingRequestForUpdate(requestId);
        request.setStatus(EmergencyLeaveStatus.REJECTED);
        request.setRejectionReason(requireText(reason, "請填寫拒絕原因"));
        request.setReviewedBy(reviewedBy);
        request.setReviewedAt(LocalDateTime.now(TAIPEI));
        return toResponse(requestsDAO.saveAndFlush(request));
    }

    private boolean canTakeOver(Long driverId, LocalDate date) {
        boolean onShift = driverScheduleService.findPublishedForDriver(driverId, date, date).stream()
                .anyMatch(shift -> shift.getShiftType() == ShiftType.WORK);
        if (!onShift) {
            return false;
        }
        AttendanceStatus status = attendanceService.findToday(driverId)
                .map(AttendanceRecordDTO::getStatus).orElse(null);
        if (!isWorking(status)) {
            return false;
        }
        return routesDAO.findByDateAndDriverIdIsNotNull(date).stream()
                .noneMatch(route -> driverId.equals(route.getDriverId()));
    }

    private boolean isWorking(AttendanceStatus status) {
        return status == AttendanceStatus.WORKING || status == AttendanceStatus.OVERTIME;
    }

    private DriversEntity requireActiveDriver(Long driverId) {
        DriversEntity driver = driversDAO.findById(driverId)
                .orElseThrow(() -> new EntityNotFoundException("找不到司機，ID：" + driverId));
        if (!Boolean.TRUE.equals(driver.getIsActive())) {
            throw new IllegalArgumentException("司機已停職，不能參與臨時請假或接手");
        }
        return driver;
    }

    private EmergencyLeaveRequestsEntity findPendingRequest(Long requestId) {
        EmergencyLeaveRequestsEntity request = requestsDAO.findById(requestId)
                .orElseThrow(() -> new EntityNotFoundException("找不到臨時請假申請"));
        if (request.getStatus() != EmergencyLeaveStatus.PENDING) {
            throw new IllegalArgumentException("這筆臨時請假申請已審核");
        }
        return request;
    }

    private EmergencyLeaveRequestsEntity findPendingRequestForUpdate(Long requestId) {
        EmergencyLeaveRequestsEntity request = requestsDAO.findForUpdate(requestId)
                .orElseThrow(() -> new EntityNotFoundException("找不到臨時請假申請"));
        if (request.getStatus() != EmergencyLeaveStatus.PENDING) {
            throw new IllegalArgumentException("這筆臨時請假申請已審核");
        }
        return request;
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

    private EmergencyLeaveResponse toResponse(EmergencyLeaveRequestsEntity request) {
        String driverName = driversDAO.findById(request.getDriverId())
                .map(DriversEntity::getName).orElse(null);
        String replacementName = request.getReplacementDriverId() == null ? null
                : driversDAO.findById(request.getReplacementDriverId())
                        .map(DriversEntity::getName).orElse(null);
        String plate = vehiclesDAO.findById(request.getVehicleId())
                .map(vehicle -> vehicle.getPlateNumber()).orElse(null);
        RouteStatus routeStatus = routesDAO.findById(request.getRouteId())
                .map(RoutesEntity::getStatus).orElse(null);
        return new EmergencyLeaveResponse(
                request.getId(), request.getDriverId(), driverName, request.getWorkDate(),
                request.getAttendanceRecordId(), request.getRouteId(), routeStatus,
                request.getVehicleId(), plate, request.getReason(), request.getStatus(),
                request.getReplacementDriverId(), replacementName,
                request.getTransferredOrderCount(), false, request.getRequestedAt(),
                request.getReviewedBy(), request.getReviewedAt(), request.getRejectionReason(),
                request.getRouteReassignedAt(), request.getClockedOutAt());
    }
}
