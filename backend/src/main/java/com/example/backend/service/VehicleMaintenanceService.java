package com.example.backend.service;

import com.example.backend.constants.VehicleStatus;
import com.example.backend.dao.*;
import com.example.backend.dto.request.VehicleMaintenanceRulesDTO;
import com.example.backend.dto.respones.VehicleMaintenanceSummary;
import com.example.backend.entity.*;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

@Service
@Transactional
public class VehicleMaintenanceService {
    private final VehicleMaintenancePolicyDAO policies;
    private final VehicleMaintenanceSettingsDAO settings;
    private final VehicleMaintenanceRecordDAO records;
    private final MileageLogsDAO mileage;
    private final VehiclesDAO vehicles;
    private final DispatchBoardPushService push;

    public VehicleMaintenanceService(VehicleMaintenancePolicyDAO policies, VehicleMaintenanceSettingsDAO settings,
            VehicleMaintenanceRecordDAO records, MileageLogsDAO mileage, VehiclesDAO vehicles, DispatchBoardPushService push) {
        this.policies = policies; this.settings = settings; this.records = records;
        this.mileage = mileage; this.vehicles = vehicles; this.push = push;
    }

    @Transactional(readOnly = true)
    public VehicleMaintenanceRulesDTO rules() {
        return new VehicleMaintenanceRulesDTO(warningKm(), policies.findAll().stream()
                .sorted(Comparator.comparing(p -> p.tonnage))
                .map(p -> new VehicleMaintenanceRulesDTO.Rule(p.tonnage, p.minorIntervalKm, p.majorIntervalKm, p.retirementKm)).toList());
    }

    public VehicleMaintenanceRulesDTO saveRules(VehicleMaintenanceRulesDTO dto) {
        VehicleMaintenanceSettings config = settings.findForUpdate().orElseGet(VehicleMaintenanceSettings::new);
        Set<BigDecimal> seen = new HashSet<>();
        for (var rule : dto.policies()) {
            BigDecimal tonnage = rule.tonnage().setScale(2);
            if (!seen.add(tonnage)) throw new IllegalArgumentException("噸位規則不可重複");
            if (rule.majorIntervalKm() < rule.minorIntervalKm()) throw new IllegalArgumentException("大保間隔不可小於小保間隔");
            VehicleMaintenancePolicy policy = new VehicleMaintenancePolicy();
            policy.tonnage = tonnage; policy.minorIntervalKm = rule.minorIntervalKm();
            policy.majorIntervalKm = rule.majorIntervalKm(); policy.retirementKm = rule.retirementKm();
            policies.save(policy);
        }
        // 不刪除未帶入的既有噸位規則，避免其他主管的車輛變成無規則。
        config.warningKm = dto.warningKm(); settings.save(config);
        push.markResourcesChanged();
        return rules();
    }

    @Transactional(readOnly = true)
    public List<VehicleMaintenanceRecord> history(Long id) {
        if (!vehicles.existsById(id)) throw new EntityNotFoundException("找不到車輛");
        return records.findByVehicleIdOrderByIdDesc(id);
    }

    @Transactional(readOnly = true)
    public VehicleMaintenanceSummary summary(VehiclesEntity vehicle, Double plannedKm) {
        VehicleMaintenancePolicy policy = vehicle.getTonnage() == null ? null
                : policies.findById(vehicle.getTonnage()).orElse(null);
        List<VehicleMaintenanceRecord> history = vehicle.getId() == null ? List.of() : records.findByVehicleIdOrderByIdDesc(vehicle.getId());
        var completed = history.stream().filter(r -> "COMPLETED".equals(r.status)).toList();
        long minorCount = completed.stream().filter(r -> "MINOR".equals(r.type)).count();
        long majorCount = completed.stream().filter(r -> "MAJOR".equals(r.type)).count();
        long repairCount = completed.stream().filter(r -> "REPAIR".equals(r.type)).count();
        LocalDateTime minorAt = completed.stream().filter(r -> "MINOR".equals(r.type) && r.completedAt != null)
                .map(r -> r.completedAt).findFirst().orElse(null);
        LocalDateTime majorAt = completed.stream().filter(r -> "MAJOR".equals(r.type) && r.completedAt != null)
                .map(r -> r.completedAt).findFirst().orElse(null);
        LocalDateTime repairAt = completed.stream().filter(r -> "REPAIR".equals(r.type) && r.completedAt != null)
                .map(r -> r.completedAt).findFirst().orElse(null);
        return assess(mileageSnapshot(vehicle, history), policy, warningKm(), plannedKm, minorCount, majorCount, repairCount, minorAt, majorAt, repairAt);
    }

    @Transactional(readOnly = true)
    public VehiclesEntity mileageSnapshot(VehiclesEntity vehicle) {
        return mileageSnapshot(vehicle, vehicle.getId() == null ? List.of() : records.findByVehicleIdOrderByIdDesc(vehicle.getId()));
    }

    private VehiclesEntity mileageSnapshot(VehiclesEntity vehicle, List<VehicleMaintenanceRecord> history) {
        VehiclesEntity snapshot = new VehiclesEntity();
        snapshot.setId(vehicle.getId()); snapshot.setPlateNumber(vehicle.getPlateNumber());
        snapshot.setWarehouseId(vehicle.getWarehouseId()); snapshot.setVehicleType(vehicle.getVehicleType());
        snapshot.setCapacity(vehicle.getCapacity()); snapshot.setFuelConsumption(vehicle.getFuelConsumption());
        snapshot.setCumulativeMileageKm(vehicle.getCumulativeMileageKm()); snapshot.setTonnage(vehicle.getTonnage());
        snapshot.setStatus(vehicle.getStatus()); snapshot.setCurrentOdometerKm(vehicle.getCurrentOdometerKm());
        if (vehicle.getId() != null) {
            mileage.findLatestCompletedVehicleMileage(vehicle.getId(), org.springframework.data.domain.PageRequest.of(0, 1))
                    .stream().findFirst().ifPresent(log -> {
                        if (snapshot.getCurrentOdometerKm() == null || log.getEndOdometer() > snapshot.getCurrentOdometerKm())
                            snapshot.setCurrentOdometerKm(log.getEndOdometer());
                    });
        }
        snapshot.setLastMinorMaintenanceKm(latestCompletedBaseline(history, "MINOR", vehicle.getLastMinorMaintenanceKm()));
        snapshot.setLastMajorMaintenanceKm(latestCompletedBaseline(history, "MAJOR", vehicle.getLastMajorMaintenanceKm()));
        return snapshot;
    }

    private Integer latestCompletedBaseline(List<VehicleMaintenanceRecord> history, String type, Integer initial) {
        return history.stream().filter(r -> "COMPLETED".equals(r.status) && type.equals(r.type)
                && r.completedAt != null && r.completedOdometerKm != null)
                .findFirst().map(r -> r.completedOdometerKm).orElse(initial);
    }

    /** 僅從已存在的實際紀錄恢復快取，不接受主管自行填入的新數字。車輛須持有寫入鎖。 */
    public void restoreMileageFromHistory(VehiclesEntity vehicle) {
        VehiclesEntity snapshot = mileageSnapshot(vehicle);
        vehicle.setCurrentOdometerKm(snapshot.getCurrentOdometerKm());
        vehicle.setLastMinorMaintenanceKm(snapshot.getLastMinorMaintenanceKm());
        vehicle.setLastMajorMaintenanceKm(snapshot.getLastMajorMaintenanceKm());
    }

    /** 純函式：實際總里程只使用儀表讀數，OSRM 永不寫入里程或保養基準。 */
    public static VehicleMaintenanceSummary assess(VehiclesEntity v, VehicleMaintenancePolicy p, int warning,
            Double planned, long minorCount, long majorCount, long repairCount, LocalDateTime minorAt, LocalDateTime majorAt, LocalDateTime repairAt) {
        Integer current = v.getCurrentOdometerKm();
        Integer minor = remaining(v.getLastMinorMaintenanceKm(), p == null ? null : p.minorIntervalKm, current);
        Integer major = remaining(v.getLastMajorMaintenanceKm(), p == null ? null : p.majorIntervalKm, current);
        Integer retirement = p == null || current == null ? null : p.retirementKm - current;
        Double d = planned == null ? 0.0 : planned;
        List<String> reasons = new ArrayList<>();
        boolean blocked = v.getStatus() != VehicleStatus.AVAILABLE;
        if (blocked) reasons.add(v.getStatus() == VehicleStatus.RETIRED ? "車輛已退役" : "車輛正在保養／維修");
        boolean unknown = current == null || p == null || minor == null || major == null;
        if (current == null) reasons.add("尚無綁定此車的初始或司機實際里程紀錄");
        if (p == null) reasons.add("請設定車輛噸位及該噸位保養與退役規則");
        if (v.getLastMinorMaintenanceKm() == null) reasons.add("尚無初始小保基準或已完成小保紀錄");
        if (v.getLastMajorMaintenanceKm() == null) reasons.add("尚無初始大保基準或已完成大保紀錄");
        if (!Double.isFinite(d) || d < 0) { unknown = true; reasons.add("完整含回程 OSRM 里程無效"); d = null; }
        Double pm = project(minor, d), pg = project(major, d), pr = project(retirement, d);
        boolean warned = false;
        Double[] values = {pm, pg, pr}; String[] labels = {"小保", "大保", "退役"};
        for (int i = 0; i < values.length; i++) {
            Double value = values[i];
            if (value == null) continue;
            if (value < 0) { blocked = true; reasons.add(labels[i] + "里程將超過 " + String.format(Locale.ROOT, "%.1f", -value) + " km"); }
            else if (value <= warning) { warned = true; reasons.add("距離" + labels[i] + "剩 " + String.format(Locale.ROOT, "%.1f", value) + " km"); }
        }
        String decision = blocked ? "BLOCKED" : unknown ? "UNKNOWN" : warned ? "WARNING" : "NORMAL";
        return new VehicleMaintenanceSummary(current, minor, major, retirement, warning, planned,
                pm, pg, pr, decision, List.copyOf(reasons), minorCount, majorCount, repairCount, minorAt, majorAt, repairAt);
    }

    private static Integer remaining(Integer baseline, Integer interval, Integer current) {
        if (baseline == null || interval == null || current == null) return null;
        return Math.toIntExact((long) baseline + interval - current);
    }
    private static Double project(Integer remaining, Double planned) { return remaining == null || planned == null ? null : remaining - planned; }
    private int warningKm() { return settings.findById(1L).map(s -> s.warningKm).orElse(500); }

    public void assertCanDispatch(VehiclesEntity v, double plannedKm) {
        // 規則修改與派車預檢序列化：禁止預檢期間用到一半新、一半舊規則。
        settings.findForUpdate();
        var result = summary(v, plannedKm);
        if ("BLOCKED".equals(result.decision()) || "UNKNOWN".equals(result.decision()))
            throw new IllegalArgumentException(v.getPlateNumber() + " 禁止出車：" + String.join("；", result.reasons()));
    }

    public void changeStatus(VehiclesEntity vehicle, VehicleStatus next) {
        restoreMileageFromHistory(vehicle);
        VehicleStatus previous = vehicle.getStatus();
        if (previous == next) return;
        if (!mileage.findOpenByVehicleForUpdate(vehicle.getId()).isEmpty())
            throw new IllegalArgumentException("車輛尚未收車，不能變更保養或退役狀態");
        VehicleMaintenanceRecord active = records.findByActiveVehicleId(vehicle.getId()).orElse(null);
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Taipei"));
        if (next == VehicleStatus.MINOR_MAINTENANCE || next == VehicleStatus.MAJOR_MAINTENANCE || next == VehicleStatus.MAINTENANCE) {
            if (active != null) throw new IllegalArgumentException("請先完成或取消目前保養／維修，再建立另一次送修");
            if (next != VehicleStatus.MAINTENANCE && vehicle.getCurrentOdometerKm() == null)
                throw new IllegalArgumentException("車輛沒有可信的初始或司機里程紀錄，不能建立送修里程");
            VehicleMaintenanceRecord record = new VehicleMaintenanceRecord();
            record.vehicleId = vehicle.getId(); record.activeVehicleId = vehicle.getId();
            record.type = next == VehicleStatus.MINOR_MAINTENANCE ? "MINOR" : next == VehicleStatus.MAJOR_MAINTENANCE ? "MAJOR" : "REPAIR";
            record.status = "ACTIVE"; record.sentAt = now; record.sentOdometerKm = vehicle.getCurrentOdometerKm();
            record.recordedBy = actor(); records.save(record);
        } else if (active != null) {
            if (next != VehicleStatus.AVAILABLE) throw new IllegalArgumentException("請先完成或取消目前保養／維修，再改車輛狀態");
            active.status = "COMPLETED"; active.completedAt = now;
            active.completedOdometerKm = vehicle.getCurrentOdometerKm(); active.completedBy = actor(); active.activeVehicleId = null;
            if ("MINOR".equals(active.type)) vehicle.setLastMinorMaintenanceKm(active.completedOdometerKm);
            else if ("MAJOR".equals(active.type)) vehicle.setLastMajorMaintenanceKm(active.completedOdometerKm);
            records.save(active);
        }
        vehicle.setStatus(next);
    }

    public void cancel(Long id) {
        VehiclesEntity vehicle = vehicles.findByIdForUpdate(id).orElseThrow(() -> new EntityNotFoundException("找不到車輛"));
        VehicleMaintenanceRecord active = records.findByActiveVehicleId(id).orElseThrow(() -> new IllegalArgumentException("沒有進行中的保養／維修"));
        active.status = "CANCELLED"; active.cancelledAt = LocalDateTime.now(ZoneId.of("Asia/Taipei")); active.activeVehicleId = null;
        records.save(active); vehicle.setStatus(VehicleStatus.AVAILABLE); vehicles.save(vehicle); push.markResourcesChanged();
    }

    public boolean hasHistory(Long id) { return records.existsByVehicleId(id); }
    private static String actor() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null ? "SYSTEM" : authentication.getName();
    }
}
