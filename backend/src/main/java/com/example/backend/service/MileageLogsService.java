package com.example.backend.service;

import com.example.backend.constants.RouteStatus;
import com.example.backend.constants.OrderStatus;
import com.example.backend.constants.EmergencyLeaveStatus;
import com.example.backend.dao.DriversDAO;
import com.example.backend.dao.EmergencyLeaveRequestsDAO;
import com.example.backend.dao.MileageLogsDAO;
import com.example.backend.dao.OrdersDAO;
import com.example.backend.dao.RoutesDAO;
import com.example.backend.dao.VehiclesDAO;
import com.example.backend.dto.request.MileageRequestDTO;
import com.example.backend.dto.respones.MileageLogResponse;
import com.example.backend.entity.DriversEntity;
import com.example.backend.entity.MileageLogsEntity;
import com.example.backend.entity.RoutesEntity;
import com.example.backend.entity.VehiclesEntity;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

@Service
@Transactional
public class MileageLogsService {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    private final MileageLogsDAO mileageLogsDAO;
    private final DriversDAO driversDAO;
    private final RoutesDAO routesDAO;
    private final OrdersDAO ordersDAO;
    private final VehiclesDAO vehiclesDAO;
    private final AttendanceService attendanceService;
    private final VehicleMileageSettlementService vehicleMileageSettlementService;
    private final EmergencyLeaveRequestsDAO emergencyLeaveRequestsDAO;
    private final EmergencyLeaveService emergencyLeaveService;
    private final WarehouseProximityService warehouseProximityService;
    private final RouteLegMileageService routeLegMileageService;

    public MileageLogsService(
            MileageLogsDAO mileageLogsDAO,
            DriversDAO driversDAO,
            RoutesDAO routesDAO,
            OrdersDAO ordersDAO,
            VehiclesDAO vehiclesDAO,
            AttendanceService attendanceService,
            VehicleMileageSettlementService vehicleMileageSettlementService,
            EmergencyLeaveRequestsDAO emergencyLeaveRequestsDAO,
            EmergencyLeaveService emergencyLeaveService,
            WarehouseProximityService warehouseProximityService,
            RouteLegMileageService routeLegMileageService
    ) {
        this.mileageLogsDAO = mileageLogsDAO;
        this.driversDAO = driversDAO;
        this.routesDAO = routesDAO;
        this.ordersDAO = ordersDAO;
        this.vehiclesDAO = vehiclesDAO;
        this.attendanceService = attendanceService;
        this.vehicleMileageSettlementService = vehicleMileageSettlementService;
        this.emergencyLeaveRequestsDAO = emergencyLeaveRequestsDAO;
        this.emergencyLeaveService = emergencyLeaveService;
        this.warehouseProximityService = warehouseProximityService;
        this.routeLegMileageService = routeLegMileageService;
    }

    public MileageLogResponse start(Long driverId, MileageRequestDTO request) {
        LocalDateTime now = LocalDateTime.now(TAIPEI);
        LocalDate today = now.toLocalDate();
        requireActiveDriver(driverId);

        List<RoutesEntity> routes = routesDAO.findByDateAndDriverIdAndStatusOrderByIdAsc(
                today, driverId, RouteStatus.PUBLISHED);
        if (routes.isEmpty()) {
            throw new IllegalArgumentException("今天沒有已發布的配送任務，不能登記出車里程");
        }
        if (routes.size() != 1) {
            throw new IllegalStateException("同一位司機今天存在多條已發布路線，無法判斷出車車輛");
        }
        RoutesEntity route = routes.getFirst();
        if (route.getVehicleId() == null) {
            throw new IllegalStateException("已發布路線尚未指派車輛");
        }
        if (!attendanceService.isGpsUploadAllowed(driverId)) {
            throw new IllegalArgumentException("請先完成上班打卡，且不可在休息或下班狀態開始出車");
        }
        if (mileageLogsDAO.findForUpdate(driverId, today).isPresent()) {
            throw new IllegalArgumentException("今天已經登記過出車里程");
        }
        if (!mileageLogsDAO.findOpenByVehicleForUpdate(route.getVehicleId()).isEmpty()) {
            throw new IllegalArgumentException("這台車仍有其他司機尚未結束的里程，請完成交接後再出車");
        }
        Integer startOdometer = request.getOdometer();
        if (startOdometer == null) {
            throw new IllegalArgumentException("出車總里程不能留空");
        }
        VehiclesEntity vehicle = vehiclesDAO.findByIdForUpdate(route.getVehicleId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "找不到已發布路線的車輛，ID：" + route.getVehicleId()));
        if (vehicle.getCurrentOdometerKm() == null) {
            // 舊車第一次使用新制時，以實際儀表板的出車讀數建立總里程基準。
            vehicle.setCurrentOdometerKm(startOdometer);
            vehiclesDAO.save(vehicle);
        } else if (!vehicle.getCurrentOdometerKm().equals(startOdometer)) {
            throw new IllegalArgumentException(
                    "出車總里程必須與系統保存的車輛總里程一致，目前為 "
                            + vehicle.getCurrentOdometerKm() + " km");
        }

        MileageLogsEntity mileage = new MileageLogsEntity();
        mileage.setDriverId(driverId);
        mileage.setRouteId(route.getId());
        mileage.setVehicleId(route.getVehicleId());
        mileage.setDate(today);
        mileage.setStartOdometer(startOdometer);
        mileage.setStartTime(now);
        mileage.setGpsDistanceStatus("IN_PROGRESS");
        return toResponse(mileageLogsDAO.save(mileage));
    }

    public MileageLogResponse end(Long driverId, MileageRequestDTO request) {
        LocalDateTime now = LocalDateTime.now(TAIPEI);

        // 確認司機存在且目前仍在職
        requireActiveDriver(driverId);

        MileageLogsEntity mileage =
                mileageLogsDAO.findForUpdate(driverId, now.toLocalDate())
                        .orElseThrow(() ->
                                new IllegalArgumentException("今天尚未登記出車里程"));

        if (mileage.getEndTime() != null || mileage.getEndOdometer() != null) {
            throw new IllegalArgumentException("今天已經登記過收車里程");
        }

        if (mileage.getStartTime() == null) {
            throw new IllegalStateException("里程紀錄缺少出車資料，無法登記收車里程");
        }

        if (request.getOdometer() == null) {
            throw new IllegalArgumentException("收車總里程不能留空");
        }
        if (mileage.getStartOdometer() != null
                && request.getOdometer() < mileage.getStartOdometer()) {
            throw new IllegalArgumentException("收車里程不能小於出車里程");
        }

        List<String> unfinishedOrders = ordersDAO.findByRouteIdOrderBySequence(mileage.getRouteId())
                .stream()
                .filter(order -> order.getStatus() != OrderStatus.COMPLETED
                        && order.getStatus() != OrderStatus.NO_SIGNATURE)
                .map(order -> order.getOrderNumber() + "(" + order.getStatus() + ")")
                .toList();
        boolean approvedEmergencyHandover = emergencyLeaveRequestsDAO
                .existsByDriverIdAndWorkDateAndRouteIdAndStatus(
                        driverId, now.toLocalDate(), mileage.getRouteId(), EmergencyLeaveStatus.APPROVED);
        if (!unfinishedOrders.isEmpty() && !approvedEmergencyHandover) {
            throw new IllegalArgumentException(
                    "仍有任務尚未確認到貨或無人簽收，不能結束里程："
                            + String.join("、", unfinishedOrders));
        }

        warehouseProximityService.requireWithinWarehouseRadius(
                driverId, mileage.getRouteId(), now);

        mileage.setEndOdometer(request.getOdometer());
        mileage.setActualDistanceKm(request.getOdometer() - mileage.getStartOdometer());
        mileage.setEndTime(now);

        routeLegMileageService.recordReturnToWarehouse(mileage, now);

        int vehicleUpdated = vehiclesDAO.updateCurrentOdometer(
                mileage.getVehicleId(), mileage.getStartOdometer(), request.getOdometer());
        if (vehicleUpdated != 1) {
            throw new IllegalStateException("車輛總里程已變更，請重新確認儀表板讀數");
        }

        vehicleMileageSettlementService.settle(mileage, now);
        emergencyLeaveService.finalizeApprovedHandover(
                driverId, now.toLocalDate(), mileage.getRouteId(), now);

        return toResponse(mileageLogsDAO.save(mileage));
    }

    /** GPS 或 OSRM 暫時失敗時，可由本人重新計算系統里程；已成功結算者不會重複累加。 */
    public MileageLogResponse recalculate(Long driverId) {
        LocalDate today = LocalDate.now(TAIPEI);
        requireActiveDriver(driverId);
        MileageLogsEntity mileage = mileageLogsDAO.findForUpdate(driverId, today)
                .orElseThrow(() -> new IllegalArgumentException("今天尚未登記出車里程"));
        if (mileage.getEndTime() == null) {
            throw new IllegalArgumentException("尚未登記收車，不能重新計算里程");
        }
        vehicleMileageSettlementService.settle(mileage, LocalDateTime.now(TAIPEI));
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
        Integer actualDistance = mileage.getActualDistanceKm();
        Long actualDurationMinutes = null;
        if (actualDistance == null
                && mileage.getStartOdometer() != null && mileage.getEndOdometer() != null
                && mileage.getEndOdometer() >= mileage.getStartOdometer()) {
            actualDistance = mileage.getEndOdometer() - mileage.getStartOdometer();
        }
        if (mileage.getStartTime() != null && mileage.getEndTime() != null) {
            actualDurationMinutes = Duration.between(
                    mileage.getStartTime(), mileage.getEndTime()).toMinutes();
        }
        MileageLogResponse response = new MileageLogResponse();
        response.setId(mileage.getId());
        response.setDriverId(mileage.getDriverId());
        response.setRouteId(mileage.getRouteId());
        response.setVehicleId(mileage.getVehicleId());
        response.setDate(mileage.getDate());
        response.setStartOdometer(mileage.getStartOdometer());
        response.setEndOdometer(mileage.getEndOdometer());
        response.setStartTime(mileage.getStartTime());
        response.setEndTime(mileage.getEndTime());
        response.setActualDistance(actualDistance);
        response.setActualDurationMinutes(actualDurationMinutes);
        response.setSystemDistanceKm(mileage.getGpsDistanceKm());
        response.setGpsDistanceKm(mileage.getGpsDistanceKm());
        response.setGpsDistanceStatus(mileage.getGpsDistanceStatus());
        response.setMileageSettledAt(mileage.getMileageSettledAt());
        if (mileage.getVehicleId() != null) {
            vehiclesDAO.findById(mileage.getVehicleId()).ifPresent(vehicle -> {
                response.setVehicleCurrentOdometerKm(vehicle.getCurrentOdometerKm());
                response.setVehicleSystemCumulativeMileageKm(vehicle.getCumulativeMileageKm());
                response.setVehicleCumulativeMileageKm(vehicle.getCumulativeMileageKm());
            });
        }
        return response;
    }
}
