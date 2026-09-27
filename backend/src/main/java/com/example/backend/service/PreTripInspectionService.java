package com.example.backend.service;

import com.example.backend.constants.RouteStatus;
import com.example.backend.dao.*;
import com.example.backend.dto.request.PreTripInspectionRequest;
import com.example.backend.entity.PreTripInspection;
import com.example.backend.entity.RoutesEntity;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.*;
import java.util.*;

@Service
@Transactional
public class PreTripInspectionService {
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private final PreTripInspectionDAO inspections;
    private final RoutesDAO routes;
    private final DriversDAO drivers;
    private final MileageLogsDAO mileage;
    private final PreTripPhotoStorageService photos;
    public PreTripInspectionService(PreTripInspectionDAO inspections, RoutesDAO routes, DriversDAO drivers,
                                    MileageLogsDAO mileage, PreTripPhotoStorageService photos) {
        this.inspections = inspections; this.routes = routes; this.drivers = drivers; this.mileage = mileage; this.photos = photos;
    }

    public record Result(Long id, Long routeId, Long vehicleId, boolean completed, boolean passed,
                         BigDecimal alcoholMgL, LocalDateTime submittedAt, String message) {}

    @Transactional(readOnly = true)
    public Result find(Long driverId, Long routeId) {
        RoutesEntity route = authorize(driverId, routeId, false);
        return latest(route).map(this::result).orElse(new Result(null, routeId, route.getVehicleId(), false, false,
                null, null, "請先完成酒測、車輛檢點、行車紀錄器檢查及照片，再進行倉庫點交"));
    }

    public Result submit(Long driverId, PreTripInspectionRequest request, MultipartFile alcohol,
                         MultipartFile vehicle, MultipartFile dashcam) {
        RoutesEntity route = authorize(driverId, request.routeId(), true);
        if (request.alcoholMgL() == null || request.alcoholMgL().signum() < 0 || !request.allChecked())
            throw new IllegalArgumentException("請填酒測濃度並逐項确认所有檢查正常");
        List<String> stored = new ArrayList<>();
        try {
            stored.add(photos.store(alcohol)); stored.add(photos.store(vehicle)); stored.add(photos.store(dashcam));
            PreTripInspection record = new PreTripInspection();
            record.routeId = route.getId(); record.driverId = driverId; record.vehicleId = route.getVehicleId();
            record.routeVersion = route.getVersion(); record.workDate = route.getDate();
            record.alcoholMgL = request.alcoholMgL(); record.alcoholTested = request.alcoholTested();
            record.headlights = request.headlights(); record.taillights = request.taillights();
            record.turnSignals = request.turnSignals(); record.brakeLights = request.brakeLights();
            record.frontLeftTire = request.frontLeftTire(); record.frontRightTire = request.frontRightTire();
            record.rearLeftTire = request.rearLeftTire(); record.rearRightTire = request.rearRightTire(); record.dashcam = request.dashcam();
            record.alcoholPhoto = stored.get(0); record.vehiclePhoto = stored.get(1); record.dashcamPhoto = stored.get(2);
            // 此處是系統採用的零酒精出車規則，不宣稱為法定門檻。
            record.passed = request.alcoholMgL().compareTo(BigDecimal.ZERO) == 0;
            record.submittedAt = LocalDateTime.now(TAIPEI);
            Result response = result(inspections.saveAndFlush(record));
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) stored.forEach(photos::discard);
                }
            });
            return response;
        } catch (RuntimeException e) { stored.forEach(photos::discard); throw e; }
    }

    /** 點交／里程起登與撤回共用路線鎖，避免檢查通过後任務被撤回卻仍能出車。 */
    public RoutesEntity requirePassed(Long driverId, Long routeId) {
        RoutesEntity route = authorize(driverId, routeId, true);
        if (latest(route).filter(record -> record.passed).isEmpty())
            throw new IllegalArgumentException("點交前安全檢查尚未通過，請先完成酒測、車輛檢點、行車紀錄器與三張照片");
        return route;
    }

    /** 只有檢查、尚未點交或起登里程的任務可撤回；留下作廢稽核紀錄。 */
    public void prepareWithdraw(LocalDate date) {
        for (RoutesEntity initial : routes.findByDate(date).stream().sorted(Comparator.comparing(RoutesEntity::getId)).toList()) {
            RoutesEntity route = routes.findForUpdate(initial.getId()).orElseThrow();
            if (route.getStatus() != RouteStatus.PUBLISHED) continue;
            if (!mileage.findAllByRouteIdOrderByStartTimeAsc(route.getId()).isEmpty())
                throw new IllegalArgumentException("路線 #" + route.getId() + " 已登記出車里程，不能撤回；換人請走司機交接");
            for (PreTripInspection record : inspections.findByRouteIdAndInvalidatedAtIsNull(route.getId()))
                record.invalidatedAt = LocalDateTime.now(TAIPEI);
        }
    }

    @Transactional(readOnly = true)
    public Path photo(Long driverId, Long id, String kind) {
        PreTripInspection record = inspections.findById(id).orElseThrow(() -> new EntityNotFoundException("找不到檢查紀錄"));
        if (!driverId.equals(record.driverId)) throw new IllegalArgumentException("不能讀取其他司機的檢查照片");
        String file = switch (kind) { case "alcohol" -> record.alcoholPhoto; case "vehicle" -> record.vehiclePhoto;
            case "dashcam" -> record.dashcamPhoto; default -> throw new IllegalArgumentException("照片類型不合法"); };
        return photos.resolve(file);
    }
    private RoutesEntity authorize(Long driverId, Long routeId, boolean lock) {
        if (!drivers.findById(driverId).map(driver -> Boolean.TRUE.equals(driver.getIsActive())).orElse(false))
            throw new IllegalArgumentException("司機帳號已停用或不存在");
        RoutesEntity route = (lock ? routes.findForUpdate(routeId) : routes.findById(routeId))
                .orElseThrow(() -> new EntityNotFoundException("找不到配送任務"));
        if (!driverId.equals(route.getDriverId()) || route.getStatus() != RouteStatus.PUBLISHED
                || !LocalDate.now(TAIPEI).equals(route.getDate()) || route.getVehicleId() == null)
            throw new IllegalArgumentException("只能操作今天已發布給本人的任務；任務可能已撤回或改派");
        return route;
    }
    private Optional<PreTripInspection> latest(RoutesEntity route) {
        return inspections.findFirstByRouteIdAndDriverIdAndVehicleIdAndRouteVersionAndInvalidatedAtIsNullOrderByIdDesc(
                route.getId(), route.getDriverId(), route.getVehicleId(), route.getVersion());
    }
    private Result result(PreTripInspection record) {
        return new Result(record.id, record.routeId, record.vehicleId, true, record.passed, record.alcoholMgL, record.submittedAt,
                record.passed ? "檢查已通過，可以進行倉庫點交" : "酒測非 0.00 mg/L，禁止點交與出車，請通知主管撤回任務");
    }
}
