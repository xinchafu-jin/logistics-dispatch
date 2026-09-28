package com.example.backend.service;

import com.example.backend.constants.MaintenanceRecordStatus;
import com.example.backend.constants.MaintenanceRecordType;
import com.example.backend.constants.VehicleStatus;
import com.example.backend.dao.MileageLogsDAO;
import com.example.backend.dao.VehicleMaintenanceRecordsDAO;
import com.example.backend.dao.VehicleMaintenanceSettingsDAO;
import com.example.backend.dao.VehiclesDAO;
import com.example.backend.dto.request.VehicleMaintenanceSettingsDTO;
import com.example.backend.dto.respones.VehicleMaintenanceRecordResponse;
import com.example.backend.dto.respones.VehicleMaintenanceSummaryResponse;
import com.example.backend.entity.VehicleMaintenanceRecordsEntity;
import com.example.backend.entity.VehicleMaintenanceSettingsEntity;
import com.example.backend.entity.VehiclesEntity;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 車輛保養與退役：用行車紀錄器里程算出離小保、大保、退役還有多遠，派車和出車前預估跑完這趟會不會超過。
 *
 * <p>算法：剩下的公里數＝基準＋間隔－目前行車紀錄器里程（退役是退役總里程－目前里程）；
 * 跑完這趟後＝剩下的－這趟預計里程（含回倉）。間隔和退役總里程是每台車自己的設定（vehicles 上的欄位）。</p>
 *
 * <ul>
 *     <li>跑完會超過（負數），或車在保養／維修、已退役 → 擋發布、擋出車</li>
 *     <li>跑完剩 0～提醒公里數 → 只提醒</li>
 *     <li>缺間隔、基準或里程，算不出來 → 只提醒、不擋：這個功能上線前的舊車大多沒資料，
 *     擋的話所有路線都發布不了；等資料補齊才開始把關。算得出來的項目照樣會擋、會提醒</li>
 * </ul>
 *
 * <p>里程只用司機填的行車紀錄器里程（vehicles.current_odometer_km），不用 GPS 或 OSRM 的距離；
 * OSRM 只拿來預估「這趟要跑多遠」。</p>
 */
@Service
@Transactional
public class VehicleMaintenanceService {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final int DEFAULT_WARNING_KM = 500;

    private final VehicleMaintenanceSettingsDAO settingsDAO;
    private final VehicleMaintenanceRecordsDAO recordsDAO;
    private final VehiclesDAO vehiclesDAO;
    private final MileageLogsDAO mileageLogsDAO;

    public VehicleMaintenanceService(
            VehicleMaintenanceSettingsDAO settingsDAO,
            VehicleMaintenanceRecordsDAO recordsDAO,
            VehiclesDAO vehiclesDAO,
            MileageLogsDAO mileageLogsDAO
    ) {
        this.settingsDAO = settingsDAO;
        this.recordsDAO = recordsDAO;
        this.vehiclesDAO = vehiclesDAO;
        this.mileageLogsDAO = mileageLogsDAO;
    }

    // ── 全車共用的設定 ────────────────────────────────────

    @Transactional(readOnly = true)
    public VehicleMaintenanceSettingsDTO settings() {
        VehicleMaintenanceSettingsDTO settings = new VehicleMaintenanceSettingsDTO();
        settings.setWarningKm(warningKm());
        return settings;
    }

    public VehicleMaintenanceSettingsDTO saveSettings(VehicleMaintenanceSettingsDTO dto) {
        VehicleMaintenanceSettingsEntity settings = settingsDAO.findById(VehicleMaintenanceSettingsEntity.SINGLE_ROW_ID)
                .orElseGet(VehicleMaintenanceSettingsEntity::new);
        settings.setWarningKm(dto.getWarningKm());
        settingsDAO.save(settings);
        return settings();
    }

    // ── 保養狀況 ──────────────────────────────────────────

    /**
     * 這台車的保養狀況。plannedKm 是這趟預計要跑的公里數（含回倉）；
     * 只是看現況（人車資源頁）時傳 null，就當作跑 0 公里：已經超過、快到了照樣會標出來。
     */
    @Transactional(readOnly = true)
    public VehicleMaintenanceSummaryResponse summary(VehiclesEntity vehicle, Double plannedKm) {
        List<VehicleMaintenanceRecordsEntity> history = List.of();
        if (vehicle.getId() != null) {
            history = recordsDAO.findAllByVehicleIdOrderByIdDesc(vehicle.getId());
        }
        VehicleMaintenanceSummaryResponse summary = assess(vehicle, warningKm(), plannedKm);
        fillCounts(summary, history);
        return summary;
    }

    /**
     * 發布、出車前呼叫：跑完這趟會超過、或車在保養／維修、已退役就擋；算不出來（缺資料）不擋。
     * 呼叫端要先鎖住這台車：間隔也存在車上，主管同時改間隔（VehiclesService.update 也鎖車）會排隊。
     */
    public void assertCanDispatch(VehiclesEntity vehicle, Double plannedKm) {
        VehicleMaintenanceSummaryResponse summary = summary(vehicle, plannedKm);
        if (VehicleMaintenanceSummaryResponse.BLOCKED.equals(summary.getDecision())) {
            throw new IllegalArgumentException(vehicle.getPlateNumber() + " 不能出車："
                    + String.join("；", summary.getReasons()));
        }
    }

    // ── 送修、完成、取消 ──────────────────────────────────

    /**
     * 車輛改狀態時呼叫（VehiclesService 新增、修改車輛）：
     * <ul>
     *     <li>改成送小保、送大保、送維修 → 新增一筆進行中的送修紀錄</li>
     *     <li>從送修改回可用 → 那一筆完成；小保、大保把當下的行車紀錄器里程記成新的基準，維修只計次數</li>
     *     <li>跟原本一樣 → 什麼都不做，重複按儲存不會多一筆紀錄</li>
     * </ul>
     * 呼叫端要先鎖住這台車（findByIdForUpdate）。
     */
    public void changeStatus(VehiclesEntity vehicle, VehicleStatus next) {
        VehicleStatus previous = vehicle.getStatus();
        if (previous == next) {
            return;
        }
        if (vehicle.getId() != null && !mileageLogsDAO.findOpenByVehicleForUpdate(vehicle.getId()).isEmpty()) {
            throw new IllegalArgumentException("這台車還沒收車，不能改保養或退役狀態");
        }

        Optional<VehicleMaintenanceRecordsEntity> active = Optional.empty();
        if (vehicle.getId() != null) {
            active = recordsDAO.findByActiveVehicleId(vehicle.getId());
        }
        if (isSentForMaintenance(next)) {
            startMaintenance(vehicle, next, active);
        } else if (active.isPresent()) {
            if (next != VehicleStatus.AVAILABLE) {
                throw new IllegalArgumentException("請先完成或取消目前的保養／維修，再改成其他狀態");
            }
            completeMaintenance(vehicle, active.get());
        }
        vehicle.setStatus(next);
    }

    /** 取消進行中的送修：不計次數、不更新基準，只留紀錄；車輛改回可用 */
    public void cancel(Long vehicleId) {
        VehiclesEntity vehicle = vehiclesDAO.findByIdForUpdate(vehicleId)
                .orElseThrow(() -> new EntityNotFoundException("找不到車輛，ID：" + vehicleId));
        VehicleMaintenanceRecordsEntity active = recordsDAO.findByActiveVehicleId(vehicleId)
                .orElseThrow(() -> new IllegalArgumentException("這台車沒有進行中的保養或維修"));
        active.setStatus(MaintenanceRecordStatus.CANCELLED);
        active.setCancelledAt(LocalDateTime.now(TAIPEI));
        active.setActiveVehicleId(null);
        recordsDAO.save(active);
        vehicle.setStatus(VehicleStatus.AVAILABLE);
        vehiclesDAO.save(vehicle);
    }

    @Transactional(readOnly = true)
    public List<VehicleMaintenanceRecordResponse> history(Long vehicleId) {
        if (!vehiclesDAO.existsById(vehicleId)) {
            throw new EntityNotFoundException("找不到車輛，ID：" + vehicleId);
        }
        List<VehicleMaintenanceRecordResponse> history = new ArrayList<>();
        for (VehicleMaintenanceRecordsEntity record : recordsDAO.findAllByVehicleIdOrderByIdDesc(vehicleId)) {
            history.add(toResponse(record));
        }
        return history;
    }

    /** 有送修紀錄的車不能刪，只能改成退役，保留歷史 */
    @Transactional(readOnly = true)
    public boolean hasHistory(Long vehicleId) {
        return recordsDAO.existsByVehicleId(vehicleId);
    }

    // ── 私有方法 ──────────────────────────────────────────

    private boolean isSentForMaintenance(VehicleStatus status) {
        return status == VehicleStatus.MINOR_MAINTENANCE
                || status == VehicleStatus.MAJOR_MAINTENANCE
                || status == VehicleStatus.MAINTENANCE;
    }

    private void startMaintenance(
            VehiclesEntity vehicle,
            VehicleStatus next,
            Optional<VehicleMaintenanceRecordsEntity> active
    ) {
        if (active.isPresent()) {
            throw new IllegalArgumentException("請先完成或取消目前的保養／維修，再送下一次");
        }
        // 小保、大保完成時要記當下里程當新基準；沒有里程就記不了。維修不需要，車禍當下可能還沒有里程
        if (next != VehicleStatus.MAINTENANCE && vehicle.getCurrentOdometerKm() == null) {
            throw new IllegalArgumentException("這台車還沒有行車紀錄器里程，不能送保養；請先補上目前的里程");
        }
        VehicleMaintenanceRecordsEntity record = new VehicleMaintenanceRecordsEntity();
        record.setVehicleId(vehicle.getId());
        record.setActiveVehicleId(vehicle.getId());
        record.setType(recordTypeOf(next));
        record.setStatus(MaintenanceRecordStatus.ACTIVE);
        record.setSentAt(LocalDateTime.now(TAIPEI));
        record.setSentOdometerKm(vehicle.getCurrentOdometerKm());
        record.setRecordedBy(actor());
        recordsDAO.save(record);
    }

    private void completeMaintenance(VehiclesEntity vehicle, VehicleMaintenanceRecordsEntity active) {
        active.setStatus(MaintenanceRecordStatus.COMPLETED);
        active.setCompletedAt(LocalDateTime.now(TAIPEI));
        active.setCompletedOdometerKm(vehicle.getCurrentOdometerKm());
        active.setCompletedBy(actor());
        active.setActiveVehicleId(null);
        // 小保不重設大保、大保不重設小保；維修兩個都不動
        if (active.getType() == MaintenanceRecordType.MINOR) {
            vehicle.setLastMinorMaintenanceKm(vehicle.getCurrentOdometerKm());
        } else if (active.getType() == MaintenanceRecordType.MAJOR) {
            vehicle.setLastMajorMaintenanceKm(vehicle.getCurrentOdometerKm());
        }
        recordsDAO.save(active);
    }

    private MaintenanceRecordType recordTypeOf(VehicleStatus status) {
        if (status == VehicleStatus.MINOR_MAINTENANCE) {
            return MaintenanceRecordType.MINOR;
        }
        if (status == VehicleStatus.MAJOR_MAINTENANCE) {
            return MaintenanceRecordType.MAJOR;
        }
        return MaintenanceRecordType.REPAIR;
    }

    private int warningKm() {
        Optional<VehicleMaintenanceSettingsEntity> settings = settingsDAO.findById(VehicleMaintenanceSettingsEntity.SINGLE_ROW_ID);
        if (settings.isEmpty()) {
            return DEFAULT_WARNING_KM;
        }
        return settings.get().getWarningKm();
    }

    /**
     * 算剩下的公里數與結論。沒有要評估的趟次（plannedKm 是 null）時用 0 公里算，
     * 回傳的 plannedKm 仍是 null，前端靠它決定要不要顯示「本趟完成後（預估）」。
     */
    private VehicleMaintenanceSummaryResponse assess(VehiclesEntity vehicle, int warningKm, Double plannedKm) {
        Integer current = vehicle.getCurrentOdometerKm();
        Integer minorRemaining = remaining(
                vehicle.getLastMinorMaintenanceKm(), vehicle.getMinorMaintenanceIntervalKm(), current);
        Integer majorRemaining = remaining(
                vehicle.getLastMajorMaintenanceKm(), vehicle.getMajorMaintenanceIntervalKm(), current);
        Integer retirementRemaining = null;
        if (vehicle.getRetirementKm() != null && current != null) {
            retirementRemaining = vehicle.getRetirementKm() - current;
        }

        List<String> reasons = new ArrayList<>();
        boolean blocked = false;
        boolean unknown = false;
        if (vehicle.getStatus() == VehicleStatus.RETIRED) {
            blocked = true;
            reasons.add("車輛已退役");
        } else if (vehicle.getStatus() != VehicleStatus.AVAILABLE) {
            blocked = true;
            reasons.add("車輛正在保養或維修");
        }
        if (current == null) {
            unknown = true;
            reasons.add("還沒有行車紀錄器里程");
        }
        // 畫面上三個一起填（VehiclesService 會擋），缺任何一個都當作還沒設定
        if (vehicle.getMinorMaintenanceIntervalKm() == null
                || vehicle.getMajorMaintenanceIntervalKm() == null
                || vehicle.getRetirementKm() == null) {
            unknown = true;
            reasons.add("還沒設定保養間隔與退役里程");
        }
        if (vehicle.getLastMinorMaintenanceKm() == null) {
            unknown = true;
            reasons.add("還沒有小保基準");
        }
        if (vehicle.getLastMajorMaintenanceKm() == null) {
            unknown = true;
            reasons.add("還沒有大保基準");
        }

        double tripKm = 0.0;
        if (plannedKm != null) {
            if (Double.isFinite(plannedKm) && plannedKm >= 0) {
                tripKm = plannedKm;
            } else {
                unknown = true;
                reasons.add("這趟的預估里程無效，無法預估保養");
            }
        }
        Double projectedMinor = project(minorRemaining, tripKm);
        Double projectedMajor = project(majorRemaining, tripKm);
        Double projectedRetirement = project(retirementRemaining, tripKm);

        boolean warned = false;
        String[] labels = {"小保", "大保", "退役"};
        Double[] projected = {projectedMinor, projectedMajor, projectedRetirement};
        for (int index = 0; index < labels.length; index++) {
            Double value = projected[index];
            if (value == null) {
                continue;
            }
            if (value < 0) {
                blocked = true;
                reasons.add(labels[index] + "里程將超過 " + km(-value) + " km");
            } else if (value <= warningKm) {
                warned = true;
                reasons.add("距離" + labels[index] + "剩 " + km(value) + " km");
            }
        }

        VehicleMaintenanceSummaryResponse summary = new VehicleMaintenanceSummaryResponse();
        summary.setCurrentOdometerKm(current);
        summary.setMinorRemainingKm(minorRemaining);
        summary.setMajorRemainingKm(majorRemaining);
        summary.setRetirementRemainingKm(retirementRemaining);
        summary.setWarningKm(warningKm);
        summary.setPlannedKm(plannedKm);
        summary.setProjectedMinorKm(projectedMinor);
        summary.setProjectedMajorKm(projectedMajor);
        summary.setProjectedRetirementKm(projectedRetirement);
        summary.setReasons(List.copyOf(reasons));
        summary.setDecision(decision(blocked, unknown, warned));
        return summary;
    }

    /**
     * 擋 > 提醒 > 算不出來 > 正常：算得出來的項目只要有一項會超過就擋、快到了就提醒，不管其他項有沒有資料。
     * 「提醒」排在「算不出來」前面：只知道上次小保、不知道上次大保的車，快到小保時看板也要變黃
     * （看板不顯示「算不出來」），不能等到跑完會超過才直接變紅。缺的資料照樣寫在 reasons 裡。
     */
    private String decision(boolean blocked, boolean unknown, boolean warned) {
        if (blocked) {
            return VehicleMaintenanceSummaryResponse.BLOCKED;
        }
        if (warned) {
            return VehicleMaintenanceSummaryResponse.WARNING;
        }
        if (unknown) {
            return VehicleMaintenanceSummaryResponse.UNKNOWN;
        }
        return VehicleMaintenanceSummaryResponse.NORMAL;
    }

    /** 基準＋間隔－目前里程；缺任何一個就算不出來 */
    private Integer remaining(Integer baseline, Integer interval, Integer current) {
        if (baseline == null || interval == null || current == null) {
            return null;
        }
        return baseline + interval - current;
    }

    private Double project(Integer remaining, double tripKm) {
        if (remaining == null) {
            return null;
        }
        return remaining - tripKm;
    }

    private String km(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    /** 完成的小保、大保、維修各幾次，以及最近一次的時間（歷史是新的在前，第一筆就是最近的） */
    private void fillCounts(VehicleMaintenanceSummaryResponse summary, List<VehicleMaintenanceRecordsEntity> history) {
        long minorCount = 0;
        long majorCount = 0;
        long repairCount = 0;
        for (VehicleMaintenanceRecordsEntity record : history) {
            if (record.getStatus() != MaintenanceRecordStatus.COMPLETED) {
                continue;
            }
            if (record.getType() == MaintenanceRecordType.MINOR) {
                minorCount++;
                if (summary.getLastMinorAt() == null) {
                    summary.setLastMinorAt(record.getCompletedAt());
                }
            } else if (record.getType() == MaintenanceRecordType.MAJOR) {
                majorCount++;
                if (summary.getLastMajorAt() == null) {
                    summary.setLastMajorAt(record.getCompletedAt());
                }
            } else {
                repairCount++;
                if (summary.getLastRepairAt() == null) {
                    summary.setLastRepairAt(record.getCompletedAt());
                }
            }
        }
        summary.setMinorCount(minorCount);
        summary.setMajorCount(majorCount);
        summary.setRepairCount(repairCount);
    }

    private VehicleMaintenanceRecordResponse toResponse(VehicleMaintenanceRecordsEntity record) {
        VehicleMaintenanceRecordResponse response = new VehicleMaintenanceRecordResponse();
        response.setId(record.getId());
        response.setVehicleId(record.getVehicleId());
        response.setType(record.getType());
        response.setStatus(record.getStatus());
        response.setSentAt(record.getSentAt());
        response.setSentOdometerKm(record.getSentOdometerKm());
        response.setCompletedAt(record.getCompletedAt());
        response.setCompletedOdometerKm(record.getCompletedOdometerKm());
        response.setCancelledAt(record.getCancelledAt());
        response.setRecordedBy(record.getRecordedBy());
        response.setCompletedBy(record.getCompletedBy());
        return response;
    }

    /** 誰送修、誰完成：取登入的主管帳號；排程等沒有登入者的情況記 SYSTEM */
    private String actor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return "SYSTEM";
        }
        return authentication.getName();
    }
}
