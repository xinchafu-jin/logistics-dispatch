package com.example.backend.service;

import com.example.backend.constants.VehicleStatus;
import com.example.backend.dao.DriversDAO;
import com.example.backend.dao.RoutesDAO;
import com.example.backend.dao.VehiclesDAO;
import com.example.backend.dto.request.OptimizeSlotsDTO;
import com.example.backend.entity.DriversEntity;
import com.example.backend.entity.RoutesEntity;
import com.example.backend.entity.VehiclesEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;

/**
 * 把看板上的格子（司機、車輛皆選填）轉成自動排車要用的車輛清單與「車輛 → 司機」對照。
 *
 * <p>以人為準：只填司機的格子自動配一台還沒被選走的可用車。
 * 有問題的格子不讓整批失敗，而是略過或不帶司機，原因放進 notices 回給畫面。</p>
 */
@Service
@Transactional(readOnly = true)
public class DispatchSlotService {

    private final VehiclesDAO vehiclesDAO;
    private final RoutesDAO routesDAO;
    private final DriversDAO driversDAO;

    public DispatchSlotService(VehiclesDAO vehiclesDAO, RoutesDAO routesDAO, DriversDAO driversDAO) {
        this.vehiclesDAO = vehiclesDAO;
        this.routesDAO = routesDAO;
        this.driversDAO = driversDAO;
    }

    public SlotPlan plan(LocalDate date, Long warehouseId, List<OptimizeSlotsDTO.Slot> slots) {
        List<OptimizeSlotsDTO.Slot> filled = new ArrayList<>();
        for (OptimizeSlotsDTO.Slot slot : slots) {
            if (slot.getDriverId() != null || slot.getVehicleId() != null) {
                filled.add(slot);
            }
        }
        if (filled.isEmpty()) {
            throw new IllegalArgumentException("至少要有一格選了司機或車輛，才能自動排車");
        }
        assertNoDuplicates(filled);

        // 這個倉目前能出車的車，只填司機的格子從這裡配車
        Map<Long, VehiclesEntity> available = new LinkedHashMap<>();
        for (VehiclesEntity vehicle : vehiclesDAO.findByWarehouseIdAndStatus(warehouseId, VehicleStatus.AVAILABLE)) {
            available.put(vehicle.getId(), vehicle);
        }
        // 格子指定的車先保留，配車時不能拿去給別人
        Set<Long> usedVehicleIds = new HashSet<>();
        for (OptimizeSlotsDTO.Slot slot : filled) {
            if (slot.getVehicleId() != null) {
                usedVehicleIds.add(slot.getVehicleId());
            }
        }
        // 一位司機一天只能開一條線（uk_routes_date_driver），別倉已經用掉的人不能帶進來
        Map<Long, RoutesEntity> takenElsewhere = new HashMap<>();
        for (RoutesEntity route : routesDAO.findByDateAndDriverIdIsNotNull(date)) {
            if (!warehouseId.equals(route.getWarehouseId())) {
                takenElsewhere.put(route.getDriverId(), route);
            }
        }
        Map<Long, DriversEntity> drivers = loadDrivers(filled);

        SlotPlan plan = new SlotPlan();
        for (OptimizeSlotsDTO.Slot slot : filled) {
            String who = driverName(drivers, slot.getDriverId());

            VehiclesEntity vehicle;
            if (slot.getVehicleId() != null) {
                vehicle = available.get(slot.getVehicleId());
                if (vehicle == null) {
                    plan.getNotices().add("車輛 " + slot.getVehicleId() + " 目前不能出車（維修中或不屬於這個倉），這格略過");
                    continue;
                }
            } else {
                vehicle = pickVehicle(available, usedVehicleIds);
                if (vehicle == null) {
                    plan.getNotices().add(who + " 沒有可以配的車，這格略過");
                    continue;
                }
                usedVehicleIds.add(vehicle.getId());
                plan.getNotices().add(who + " 自動配車 " + vehicle.getPlateNumber());
            }

            plan.getVehicleIds().add(vehicle.getId());
            plan.getPlateByVehicle().put(vehicle.getId(), vehicle.getPlateNumber());
            if (slot.getDriverId() == null) {
                continue;
            }
            if (!drivers.containsKey(slot.getDriverId())) {
                plan.getNotices().add("找不到司機 " + slot.getDriverId() + "，" + vehicle.getPlateNumber() + " 先不帶司機");
                continue;
            }
            if (!warehouseId.equals(drivers.get(slot.getDriverId()).getWarehouseId())) {
                plan.getNotices().add(who + " 不屬於目前倉庫，" + vehicle.getPlateNumber() + " 先不帶司機");
                continue;
            }
            if (takenElsewhere.containsKey(slot.getDriverId())) {
                plan.getNotices().add(who + " 當天已排在其他倉的路線，" + vehicle.getPlateNumber() + " 先不帶司機");
                continue;
            }
            // 班表有問題（休假、請假…）照樣帶入：看板會標紅框，派出時由 DispatchGuardService 擋
            plan.getDriverByVehicle().put(vehicle.getId(), slot.getDriverId());
            plan.getDriverNameByVehicle().put(vehicle.getId(), who);
        }
        if (plan.getVehicleIds().isEmpty()) {
            throw new IllegalArgumentException("格子裡沒有可以出車的車：" + String.join("；", plan.getNotices()));
        }
        return plan;
    }

    /** 同一個人、同一台車只能出現在一格；畫面已經擋，這裡防繞過畫面的呼叫 */
    private void assertNoDuplicates(List<OptimizeSlotsDTO.Slot> slots) {
        Set<Long> driverIds = new HashSet<>();
        Set<Long> vehicleIds = new HashSet<>();
        for (OptimizeSlotsDTO.Slot slot : slots) {
            if (slot.getDriverId() != null && !driverIds.add(slot.getDriverId())) {
                throw new IllegalArgumentException("同一位司機不能出現在兩格，ID：" + slot.getDriverId());
            }
            if (slot.getVehicleId() != null && !vehicleIds.add(slot.getVehicleId())) {
                throw new IllegalArgumentException("同一台車不能出現在兩格，ID：" + slot.getVehicleId());
            }
        }
    }

    /** 配車規則：還沒被選走的可用車裡，挑容量最大的，一台車裝得多，排不進去的單就少 */
    private VehiclesEntity pickVehicle(Map<Long, VehiclesEntity> available, Set<Long> usedVehicleIds) {
        VehiclesEntity best = null;
        for (VehiclesEntity vehicle : available.values()) {
            if (usedVehicleIds.contains(vehicle.getId())) {
                continue;
            }
            if (best == null || vehicle.getCapacity() > best.getCapacity()) {
                best = vehicle;
            }
        }
        return best;
    }

    private Map<Long, DriversEntity> loadDrivers(List<OptimizeSlotsDTO.Slot> slots) {
        List<Long> ids = new ArrayList<>();
        for (OptimizeSlotsDTO.Slot slot : slots) {
            if (slot.getDriverId() != null) {
                ids.add(slot.getDriverId());
            }
        }
        Map<Long, DriversEntity> drivers = new HashMap<>();
        for (DriversEntity driver : driversDAO.findAllById(ids)) {
            drivers.put(driver.getId(), driver);
        }
        return drivers;
    }

    private String driverName(Map<Long, DriversEntity> drivers, Long driverId) {
        if (driverId == null) {
            return "";
        }
        DriversEntity driver = drivers.get(driverId);
        if (driver == null) {
            return "司機 " + driverId;
        }
        return driver.getName();
    }

    /** 格子轉換後的結果：要排的車、每台車的司機，以及給畫面看的提醒 */
    public static class SlotPlan {

        private final List<Long> vehicleIds = new ArrayList<>();
        private final Map<Long, Long> driverByVehicle = new HashMap<>();
        private final Map<Long, String> plateByVehicle = new HashMap<>();
        private final Map<Long, String> driverNameByVehicle = new HashMap<>();
        private final List<String> notices = new ArrayList<>();

        public List<Long> getVehicleIds() {
            return vehicleIds;
        }

        public Map<Long, Long> getDriverByVehicle() {
            return driverByVehicle;
        }

        public Map<Long, String> getPlateByVehicle() {
            return plateByVehicle;
        }

        public Map<Long, String> getDriverNameByVehicle() {
            return driverNameByVehicle;
        }

        public List<String> getNotices() {
            return notices;
        }
    }
}
