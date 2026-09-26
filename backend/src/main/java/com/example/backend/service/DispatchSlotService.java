package com.example.backend.service;

import com.example.backend.constants.OrderStatus;
import com.example.backend.constants.ScheduleStatus;
import com.example.backend.constants.ShiftType;
import com.example.backend.constants.VehicleStatus;
import com.example.backend.dao.*;
import com.example.backend.dto.request.OptimizeSlotsDTO;
import com.example.backend.entity.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
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
    private final OrdersDAO ordersDAO;
    private final ScheduleMonthsDAO scheduleMonthsDAO;
    private final DriverShiftsDAO driverShiftsDAO;

    public DispatchSlotService(VehiclesDAO vehiclesDAO,
                               RoutesDAO routesDAO,
                               DriversDAO driversDAO,
                               OrdersDAO ordersDAO,
                               ScheduleMonthsDAO scheduleMonthsDAO,
                               DriverShiftsDAO driverShiftsDAO
    ) {
        this.vehiclesDAO = vehiclesDAO;
        this.routesDAO = routesDAO;
        this.driversDAO = driversDAO;
        this.ordersDAO = ordersDAO;
        this.scheduleMonthsDAO = scheduleMonthsDAO;
        this.driverShiftsDAO = driverShiftsDAO;
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

    public AutoSlots autoSlots(LocalDate date, Long warehouseId) {
        int totalBoxes = 0;
        for (OrdersEntity order : ordersDAO.findByDeliveryDateAndWarehouseId(date, warehouseId)) {
            if (order.getStatus() == OrderStatus.CONFIRMED) {
                totalBoxes += order.getBoxCount();
            }
        }
        if (totalBoxes == 0) {
            throw new IllegalArgumentException("這天這個倉沒有已確認的訂單，不用排車");
        }
        AutoSlots result = new AutoSlots();
        List<VehiclesEntity> vehicles = new ArrayList<>(
                vehiclesDAO.findByWarehouseIdAndStatus(warehouseId, VehicleStatus.AVAILABLE)
        );

        if (vehicles.isEmpty()) {
            throw new IllegalArgumentException("這個倉目前沒有可以出車的車");
        }
        vehicles.sort(Comparator.comparing(VehiclesEntity::getCapacity).reversed());
        List<VehiclesEntity> neededVehicles = new ArrayList<>();
        int capacity = 0;
        for (VehiclesEntity vehicle : vehicles) {
            if (capacity >= totalBoxes) {
                break;
            }
            neededVehicles.add(vehicle);
            capacity += vehicle.getCapacity();
        }
        if (capacity < totalBoxes) {
            result.getNotices().add("全部車的容量 " + capacity + " 箱裝不下 " + totalBoxes + " 箱，排不下的單會留在待排單");
        }

        // 月班表沒發布就不知道誰上班，不能用猜的；跟發布前檢查（DispatchGuardService）同一條規則
        ScheduleMonthsEntity month = scheduleMonthsDAO.findByScheduleMonth(date.withDayOfMonth(1)).orElse(null);
        if (month == null || month.getStatus() != ScheduleStatus.PUBLISHED) {
            throw new IllegalArgumentException(YearMonth.from(date) + " 的班表尚未發布，無法自動選司機");
        }

        // 當天班表是上班的人
        Set<Long> workingDriverIds = new HashSet<>();
        for (DriverShiftsEntity shift : driverShiftsDAO.findAllByWorkDate(date)) {
            if (shift.getShiftType() == ShiftType.WORK) {
                workingDriverIds.add(shift.getDriverId());
            }
        }

        // 別倉已經排走的人；這一倉自己草稿上的人不算，草稿等一下會清掉重排
        Set<Long> takenElsewhere = new HashSet<>();
        for (RoutesEntity route : routesDAO.findByDateAndDriverIdIsNotNull(date)) {
            if (!warehouseId.equals(route.getWarehouseId())) {
                takenElsewhere.add(route.getDriverId());
            }
        }

        List<DriversEntity> drivers = new ArrayList<>();
        for (DriversEntity driver : driversDAO.findAllById(workingDriverIds)) {
            if (Boolean.TRUE.equals(driver.getIsActive()) && !takenElsewhere.contains(driver.getId())) {
                drivers.add(driver);
            }
        }
        if (drivers.isEmpty()) {
            throw new IllegalArgumentException("當天沒有可以派的司機（要班表上班、帳號啟用、沒被別倉排走）");
        }
        // 照 id 排：規則固定，同樣的資料每次挑出來的人都一樣，排完再用下拉調整
        drivers.sort(Comparator.comparing(DriversEntity::getId));
        // 第 1 位司機配第 1 台車（容量最大），依此類推。
        // 司機比車少時只用司機人數台車，不排出沒人開的車
        int count = Math.min(neededVehicles.size(), drivers.size());
        if (drivers.size() < neededVehicles.size()) {
            result.getNotices().add("需要 " + neededVehicles.size() + " 台車，但可以派的司機只有 "
                    + drivers.size() + " 位，排不下的單會留在待排單");
        }
        for (int i = 0; i < count; i++) {
            OptimizeSlotsDTO.Slot slot = new OptimizeSlotsDTO.Slot();
            slot.setDriverId(drivers.get(i).getId());
            slot.setVehicleId(neededVehicles.get(i).getId());
            result.getSlots().add(slot);
        }

        // 第 5 步：放在提示的第一條，調度員先看到系統替他做了什麼決定
        result.getNotices().add(0, "格子空白，自動選了 " + count + " 位司機、" + count + " 台車（共 " + totalBoxes + " 箱）");
        return result;
    }

    /**
     * 同一個人、同一台車只能出現在一格；畫面已經擋，這裡防繞過畫面的呼叫
     */
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

    /**
     * 配車規則：還沒被選走的可用車裡，挑容量最大的，一台車裝得多，排不進去的單就少
     */
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

    /**
     * 格子轉換後的結果：要排的車、每台車的司機，以及給畫面看的提醒
     */
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

    public static class AutoSlots {
        private final List<OptimizeSlotsDTO.Slot> slots = new ArrayList<>();
        private final List<String> notices = new ArrayList<>();

        public List<OptimizeSlotsDTO.Slot> getSlots() {
            return slots;
        }

        public List<String> getNotices() {
            return notices;
        }
    }
}
