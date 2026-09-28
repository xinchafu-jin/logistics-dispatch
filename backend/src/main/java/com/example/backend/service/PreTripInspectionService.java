package com.example.backend.service;

import com.example.backend.constants.RouteStatus;
import com.example.backend.dao.DriversDAO;
import com.example.backend.dao.MileageLogsDAO;
import com.example.backend.dao.PreTripInspectionsDAO;
import com.example.backend.dao.RoutesDAO;
import com.example.backend.dto.request.PreTripInspectionRequestDTO;
import com.example.backend.dto.respones.PreTripInspectionResponse;
import com.example.backend.entity.DriversEntity;
import com.example.backend.entity.MileageLogsEntity;
import com.example.backend.entity.PreTripInspectionsEntity;
import com.example.backend.entity.RoutesEntity;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 出車前安全檢查：酒測＋行車紀錄器、五油、三水、二胎、四燈共 15 項。
 *
 * <p>規則：酒測 0.00、15 項全部正常才算通過。通過時由 DepartureService 在同一個交易
 * 記下出車時的行車紀錄器里程（MileageLogsService.start）；點交（DeliveryService.load）也要通過才能做。
 * 不通過也照樣存，主管才知道要換車還是換人。</p>
 *
 * <p>「通過」看的是這條路線目前這組人、車、版本底下，還沒作廢的最新一筆。
 * 換車、換人時路線版本會 +1，舊的那筆自然對不上；撤回發布時整天作廢（prepareWithdraw）。</p>
 */
@Service
@Transactional
public class PreTripInspectionService {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    private final PreTripInspectionsDAO preTripInspectionsDAO;
    private final RoutesDAO routesDAO;
    private final DriversDAO driversDAO;
    private final MileageLogsDAO mileageLogsDAO;
    private final PreTripPhotoStorageService photoStorageService;

    public PreTripInspectionService(
            PreTripInspectionsDAO preTripInspectionsDAO,
            RoutesDAO routesDAO,
            DriversDAO driversDAO,
            MileageLogsDAO mileageLogsDAO,
            PreTripPhotoStorageService photoStorageService
    ) {
        this.preTripInspectionsDAO = preTripInspectionsDAO;
        this.routesDAO = routesDAO;
        this.driversDAO = driversDAO;
        this.mileageLogsDAO = mileageLogsDAO;
        this.photoStorageService = photoStorageService;
    }

    /** 司機打開今日任務時查：這條路線目前這組人車檢查過沒、通過沒 */
    @Transactional(readOnly = true)
    public PreTripInspectionResponse findLatest(Long driverId, Long routeId) {
        RoutesEntity route = authorize(driverId, routeId, false);
        Optional<PreTripInspectionsEntity> latest = latest(route);
        if (latest.isEmpty()) {
            return notSubmitted(route);
        }
        return toResponse(latest.get());
    }

    /**
     * 送出一次檢查，通過或不通過都新增一筆。
     *
     * <p>照片先存成檔案才存資料列；中間任何一步失敗，已存的照片要刪掉，不然會留下沒有紀錄對應的檔案。</p>
     */
    public PreTripInspectionResponse submit(
            Long driverId,
            PreTripInspectionRequestDTO request,
            MultipartFile alcoholPhoto,
            MultipartFile faultPhoto
    ) {
        // 鎖住路線：跟點交、出車、撤回用同一把鎖，送到一半被撤回或改派時會排隊，不會存到一筆對不上的紀錄
        RoutesEntity route = authorize(driverId, request.getRouteId(), true);
        requireNotDeparted(route);

        PreTripInspectionsEntity inspection = newInspection(route, request);
        List<String> abnormalItems = abnormalItems(inspection);
        String note = trimToNull(request.getNote());
        if (!abnormalItems.isEmpty() && note == null) {
            throw new IllegalArgumentException("有異常項目，請說明狀況");
        }
        inspection.setNote(note);
        inspection.setPassed(isZero(request.getAlcoholMgL()) && abnormalItems.isEmpty());
        inspection.setSubmittedAt(LocalDateTime.now(TAIPEI));

        List<String> storedPhotos = new ArrayList<>();
        try {
            String alcoholPhotoName = photoStorageService.store(alcoholPhoto, "酒測器讀數");
            storedPhotos.add(alcoholPhotoName);
            inspection.setAlcoholPhoto(alcoholPhotoName);
            if (faultPhoto != null && !faultPhoto.isEmpty()) {
                String faultPhotoName = photoStorageService.store(faultPhoto, "故障");
                storedPhotos.add(faultPhotoName);
                inspection.setFaultPhoto(faultPhotoName);
            }

            PreTripInspectionsEntity saved = preTripInspectionsDAO.saveAndFlush(inspection);
            discardPhotosIfRolledBack(storedPhotos);
            return toResponse(saved);
        } catch (RuntimeException exception) {
            for (String photo : storedPhotos) {
                photoStorageService.discard(photo);
            }
            throw exception;
        }
    }

    /**
     * 記出車里程、點交之前呼叫：這條路線目前這組人車的最新一筆要通過。
     *
     * <p>會鎖住路線，跟 prepareWithdraw 同一把鎖：撤回跟出車同時發生時會排隊，
     * 不會撤回到一半司機還能出車。回傳鎖住的路線，呼叫端不用再查一次。</p>
     */
    public RoutesEntity requirePassed(Long driverId, Long routeId) {
        RoutesEntity route = authorize(driverId, routeId, true);
        Optional<PreTripInspectionsEntity> latest = latest(route);
        if (latest.isEmpty()) {
            throw new IllegalArgumentException("請先在「今日任務」完成出車前安全檢查");
        }
        if (!Boolean.TRUE.equals(latest.get().getPassed())) {
            throw new IllegalArgumentException("出車前安全檢查沒有通過，請聯絡主管處理");
        }
        return route;
    }

    /**
     * 撤回發布前呼叫（DispatchWorkflowService.withdraw）。
     *
     * <ol>
     *     <li>依路線編號由小到大，逐條鎖住當天已發布的路線</li>
     *     <li>有司機已經記了出車里程就整批擋下：撤回後重排會刪掉只剩待配送訂單的路線，
     *     出車里程的 route_id 就會指到不存在的路線（這欄沒有外鍵）</li>
     *     <li>其餘把檢查紀錄作廢，重新發布後要重做</li>
     * </ol>
     *
     * <p>「有人出車就不能撤回」放在這裡、不放 DispatchGuardService，是因為要跟 requirePassed 搶同一把路線鎖，
     * 檢查完到真正撤回之間，司機才不能插進來出車。</p>
     */
    public void prepareWithdraw(LocalDate date) {
        List<RoutesEntity> publishedRoutes = lockPublishedRoutes(date);
        requireNoDeparture(publishedRoutes);
        invalidateInspections(publishedRoutes);
    }

    /** 司機查看自己送過的照片；kind 是 alcohol（酒測器）或 fault（故障）。別人的一律拒絕 */
    @Transactional(readOnly = true)
    public Path photo(Long driverId, Long inspectionId, String kind) {
        PreTripInspectionsEntity inspection = preTripInspectionsDAO.findById(inspectionId)
                .orElseThrow(() -> new EntityNotFoundException("找不到這筆安全檢查"));
        if (!driverId.equals(inspection.getDriverId())) {
            throw new IllegalArgumentException("不能查看其他司機的檢查照片");
        }

        String fileName;
        if ("alcohol".equals(kind)) {
            fileName = inspection.getAlcoholPhoto();
        } else if ("fault".equals(kind)) {
            fileName = inspection.getFaultPhoto();
        } else {
            throw new IllegalArgumentException("照片種類只能是 alcohol 或 fault");
        }
        if (fileName == null) {
            throw new EntityNotFoundException("這次檢查沒有附故障照片");
        }
        return photoStorageService.resolve(fileName);
    }

    /**
     * 只能檢查自己今天已發布的路線。lock＝true 時用 SELECT ... FOR UPDATE 鎖住這條路線，
     * 查詢用不鎖。
     */
    private RoutesEntity authorize(Long driverId, Long routeId, boolean lock) {
        requireActiveDriver(driverId);

        Optional<RoutesEntity> found;
        if (lock) {
            found = routesDAO.findForUpdate(routeId);
        } else {
            found = routesDAO.findById(routeId);
        }
        RoutesEntity route = found.orElseThrow(() -> new EntityNotFoundException("找不到配送任務"));

        if (!driverId.equals(route.getDriverId())) {
            throw new IllegalArgumentException("這不是你的配送任務，可能已經改派，請重新整理今日任務");
        }
        if (route.getStatus() != RouteStatus.PUBLISHED) {
            throw new IllegalArgumentException("任務已撤回，請重新整理今日任務");
        }
        if (!LocalDate.now(TAIPEI).equals(route.getDate())) {
            throw new IllegalArgumentException("只能檢查今天的配送任務");
        }
        return route;
    }

    private void requireActiveDriver(Long driverId) {
        DriversEntity driver = driversDAO.findById(driverId)
                .orElseThrow(() -> new EntityNotFoundException("找不到司機，ID：" + driverId));
        if (!Boolean.TRUE.equals(driver.getIsActive())) {
            throw new IllegalArgumentException("司機帳號已停用");
        }
    }

    private Optional<PreTripInspectionsEntity> latest(RoutesEntity route) {
        return preTripInspectionsDAO
                .findFirstByRouteIdAndDriverIdAndVehicleIdAndRouteVersionAndInvalidatedAtIsNullOrderByIdDesc(
                        route.getId(), route.getDriverId(), route.getVehicleId(), route.getVersion());
    }

    /**
     * 已經出車就不能再送：最新一筆決定點交放不放行，出車後再送一筆不通過的，會把已經放行的點交擋住。
     * 還沒出車的都可以重送：沒通過的（補完水、隔一段時間重測酒精），
     * 或改版前就通過、當時出車里程分開記而還沒記到的，重送一次會一起記下里程。每一筆都留著。
     */
    private void requireNotDeparted(RoutesEntity route) {
        if (departure(route.getDriverId(), route.getDate()).isPresent()) {
            throw new IllegalArgumentException("今天已經出車，不用再做安全檢查");
        }
    }

    /** 這位司機這天的出車紀錄（出車時的行車紀錄器里程）；mileage_logs 以「司機＋日期」唯一 */
    private Optional<MileageLogsEntity> departure(Long driverId, LocalDate date) {
        return mileageLogsDAO.findByDriverIdAndDate(driverId, date);
    }

    /** 人、車、版本一律從鎖住的路線取，不信任前端送來的 */
    private PreTripInspectionsEntity newInspection(RoutesEntity route, PreTripInspectionRequestDTO request) {
        PreTripInspectionsEntity inspection = new PreTripInspectionsEntity();
        inspection.setRouteId(route.getId());
        inspection.setDriverId(route.getDriverId());
        inspection.setVehicleId(route.getVehicleId());
        inspection.setRouteVersion(route.getVersion());
        inspection.setWorkDate(route.getDate());
        inspection.setAlcoholMgL(request.getAlcoholMgL());
        inspection.setDashcam(request.getDashcam());
        inspection.setEngineOil(request.getEngineOil());
        inspection.setBrakeFluid(request.getBrakeFluid());
        inspection.setPowerSteeringFluid(request.getPowerSteeringFluid());
        inspection.setTransmissionOil(request.getTransmissionOil());
        inspection.setFuel(request.getFuel());
        inspection.setCoolant(request.getCoolant());
        inspection.setBatteryWater(request.getBatteryWater());
        inspection.setWasherFluid(request.getWasherFluid());
        inspection.setTirePressure(request.getTirePressure());
        inspection.setTireTread(request.getTireTread());
        inspection.setHeadlights(request.getHeadlights());
        inspection.setTurnSignals(request.getTurnSignals());
        inspection.setBrakeLights(request.getBrakeLights());
        inspection.setDashboardLights(request.getDashboardLights());
        return inspection;
    }

    /** 異常項目的中文名稱，順序跟司機端畫面一樣 */
    private List<String> abnormalItems(PreTripInspectionsEntity inspection) {
        List<String> abnormal = new ArrayList<>();
        addIfAbnormal(abnormal, inspection.getDashcam(), "行車紀錄器");
        addIfAbnormal(abnormal, inspection.getEngineOil(), "引擎機油");
        addIfAbnormal(abnormal, inspection.getBrakeFluid(), "煞車油");
        addIfAbnormal(abnormal, inspection.getPowerSteeringFluid(), "動力方向盤油");
        addIfAbnormal(abnormal, inspection.getTransmissionOil(), "變速箱油");
        addIfAbnormal(abnormal, inspection.getFuel(), "燃油");
        addIfAbnormal(abnormal, inspection.getCoolant(), "冷卻水");
        addIfAbnormal(abnormal, inspection.getBatteryWater(), "電瓶水");
        addIfAbnormal(abnormal, inspection.getWasherFluid(), "雨刷水");
        addIfAbnormal(abnormal, inspection.getTirePressure(), "胎壓");
        addIfAbnormal(abnormal, inspection.getTireTread(), "胎紋");
        addIfAbnormal(abnormal, inspection.getHeadlights(), "頭燈");
        addIfAbnormal(abnormal, inspection.getTurnSignals(), "方向燈");
        addIfAbnormal(abnormal, inspection.getBrakeLights(), "煞車燈");
        addIfAbnormal(abnormal, inspection.getDashboardLights(), "儀表板燈");
        return abnormal;
    }

    private void addIfAbnormal(List<String> abnormal, Boolean normal, String label) {
        if (!Boolean.TRUE.equals(normal)) {
            abnormal.add(label);
        }
    }

    /** 0.00 才算零；用 compareTo 不用 equals：BigDecimal 的 equals 會比小數位數，0 跟 0.00 不相等 */
    private boolean isZero(BigDecimal value) {
        return value.compareTo(BigDecimal.ZERO) == 0;
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    /**
     * 交易最後沒有 commit（例如之後的步驟丟例外、整筆 rollback），就把這次存的照片刪掉。
     * 沒有交易時（單元測試直接呼叫）沒有 rollback 可言，不用註冊。
     */
    private void discardPhotosIfRolledBack(List<String> storedPhotos) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        List<String> photos = List.copyOf(storedPhotos);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    for (String photo : photos) {
                        photoStorageService.discard(photo);
                    }
                }
            }
        });
    }

    /** 路線編號由小到大鎖：兩個人同時撤回時鎖的順序一樣，不會互相等到死結 */
    private List<RoutesEntity> lockPublishedRoutes(LocalDate date) {
        List<Long> routeIds = new ArrayList<>();
        for (RoutesEntity route : routesDAO.findByDate(date)) {
            if (route.getStatus() == RouteStatus.PUBLISHED) {
                routeIds.add(route.getId());
            }
        }
        Collections.sort(routeIds);

        List<RoutesEntity> locked = new ArrayList<>();
        for (Long routeId : routeIds) {
            RoutesEntity route = routesDAO.findForUpdate(routeId)
                    .orElseThrow(() -> new EntityNotFoundException("找不到路線，ID：" + routeId));
            // 等鎖的時候可能已經被別人撤回了，只處理還是已發布的
            if (route.getStatus() == RouteStatus.PUBLISHED) {
                locked.add(route);
            }
        }
        return locked;
    }

    private void requireNoDeparture(List<RoutesEntity> routes) {
        List<String> departedDrivers = new ArrayList<>();
        for (RoutesEntity route : routes) {
            if (!mileageLogsDAO.findAllByRouteIdOrderByStartTimeAsc(route.getId()).isEmpty()) {
                departedDrivers.add(driverName(route.getDriverId()));
            }
        }
        if (!departedDrivers.isEmpty()) {
            throw new IllegalArgumentException("已有司機記了出車里程，不能撤回（"
                    + String.join("、", departedDrivers) + "）");
        }
    }

    private String driverName(Long driverId) {
        if (driverId == null) {
            return "未指派司機";
        }
        Optional<DriversEntity> driver = driversDAO.findById(driverId);
        if (driver.isEmpty()) {
            return "司機 #" + driverId;
        }
        return driver.get().getName();
    }

    private void invalidateInspections(List<RoutesEntity> routes) {
        LocalDateTime now = LocalDateTime.now(TAIPEI);
        for (RoutesEntity route : routes) {
            List<PreTripInspectionsEntity> valid = preTripInspectionsDAO.findAllByRouteIdAndInvalidatedAtIsNull(route.getId());
            for (PreTripInspectionsEntity inspection : valid) {
                inspection.setInvalidatedAt(now);
            }
            preTripInspectionsDAO.saveAll(valid);
        }
    }

    private PreTripInspectionResponse notSubmitted(RoutesEntity route) {
        PreTripInspectionResponse response = new PreTripInspectionResponse();
        response.setRouteId(route.getId());
        response.setVehicleId(route.getVehicleId());
        response.setCompleted(false);
        response.setPassed(false);
        response.setMessage("請完成出車前安全檢查，通過時會一起記下出車時的行車紀錄器里程");
        return response;
    }

    private PreTripInspectionResponse toResponse(PreTripInspectionsEntity inspection) {
        List<String> abnormalItems = abnormalItems(inspection);
        PreTripInspectionResponse response = new PreTripInspectionResponse();
        response.setId(inspection.getId());
        response.setRouteId(inspection.getRouteId());
        response.setVehicleId(inspection.getVehicleId());
        response.setCompleted(true);
        response.setPassed(Boolean.TRUE.equals(inspection.getPassed()));
        response.setAlcoholMgL(inspection.getAlcoholMgL());
        response.setAbnormalItems(abnormalItems);
        response.setNote(inspection.getNote());
        response.setHasFaultPhoto(inspection.getFaultPhoto() != null);
        response.setSubmittedAt(inspection.getSubmittedAt());
        Optional<MileageLogsEntity> departure = departure(inspection.getDriverId(), inspection.getWorkDate());
        if (departure.isPresent()) {
            response.setDeparted(true);
            response.setStartOdometer(departure.get().getStartOdometer());
        }
        response.setMessage(resultMessage(inspection, abnormalItems, departure));
        return response;
    }

    private String resultMessage(
            PreTripInspectionsEntity inspection,
            List<String> abnormalItems,
            Optional<MileageLogsEntity> departure
    ) {
        if (Boolean.TRUE.equals(inspection.getPassed())) {
            if (departure.isPresent()) {
                return "檢查通過，已記下出車時的行車紀錄器里程 " + departure.get().getStartOdometer() + " km，可以點交";
            }
            // 改版前通過、當時出車里程要另外記的：再送一次檢查就會一起記下
            return "檢查通過，但還沒記出車時的行車紀錄器里程，請再送一次檢查";
        }
        boolean alcoholFailed = !isZero(inspection.getAlcoholMgL());
        if (alcoholFailed && !abnormalItems.isEmpty()) {
            return "酒測不是 0.00，而且有異常項目，不能出車，請聯絡主管";
        }
        if (alcoholFailed) {
            return "酒測不是 0.00，不能出車，請聯絡主管";
        }
        return "有異常項目，不能出車，請聯絡主管；處理好之後可以重新檢查";
    }
}
