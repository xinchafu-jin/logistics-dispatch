package com.example.backend.service;

import com.example.backend.dao.DriversDAO;
import com.example.backend.dao.OrdersDAO;
import com.example.backend.dao.StoresDAO;
import com.example.backend.dao.VehiclesDAO;
import com.example.backend.dao.WarehousesDAO;
import com.example.backend.dto.respones.DriverPortalResponse;
import com.example.backend.entity.DriversEntity;
import com.example.backend.entity.OrdersEntity;
import com.example.backend.entity.StoresEntity;
import com.example.backend.entity.VehiclesEntity;
import com.example.backend.entity.WarehousesEntity;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 司機端前台的查詢與資料組裝。
 *
 * <p>目前只使用專案既有的 DAO，不新增資料表，也不修改既有 DAO 介面。</p>
 */
// 此 Service 只提供司機端查詢，不會新增、修改或刪除資料。
@Service
@Transactional(readOnly = true)
public class DriverPortalService {

    private final DriversDAO driversDAO;
    private final OrdersDAO ordersDAO;
    private final StoresDAO storesDAO;
    private final WarehousesDAO warehousesDAO;
    private final VehiclesDAO vehiclesDAO;

    public DriverPortalService(
            DriversDAO driversDAO,
            OrdersDAO ordersDAO,
            StoresDAO storesDAO,
            WarehousesDAO warehousesDAO,
            VehiclesDAO vehiclesDAO) {
        this.driversDAO = driversDAO;
        this.ordersDAO = ordersDAO;
        this.storesDAO = storesDAO;
        this.warehousesDAO = warehousesDAO;
        this.vehiclesDAO = vehiclesDAO;
    }

    /**
     * 查詢登入司機的個人資料與既有班別設定。
     * driverId 由 Controller 從 JWT 取得，不接受前端任意指定。
     */
    public DriverPortalResponse.ProfileResponse getProfile(Long driverId) {
        DriversEntity driver = findDriver(driverId);
        return new DriverPortalResponse.ProfileResponse(
                driver.getId(),
                driver.getAccount(),
                driver.getName(),
                driver.getPhone(),
                driver.getWorkStart(),
                driver.getWorkEnd(),
                driver.getRestDuration(),
                driver.getMaxOvertimeMinutes(),
                driver.getIsActive());
    }

    /**
     * 查詢某日的配送任務。
     *
     * <p>先一次取出該日訂單，再批次載入門市與車輛資料，
     * 避免每筆訂單個別呼叫 DAO 造成 N+1 查詢。</p>
     */
    public List<DriverPortalResponse.TaskResponse> getTasks(Long driverId, LocalDate date) {
        findDriver(driverId);
        List<OrdersEntity> orders = findOrders(driverId, date, date);
        Map<Long, StoresEntity> stores = storesById(orders);
        Map<Long, VehiclesEntity> vehicles = vehiclesById(orders);

        return orders.stream()
                .map(order -> toTaskResponse(order, stores.get(order.getStoreId()),
                        vehicles.get(order.getAssignedVehicleId())))
                .toList();
    }

    /**
     * 組裝地圖畫面資料：以 routeId 分組後，回傳倉庫座標與各門市停靠點。
     * 前端可依 stops 的 sequence 依序繪製配送路線與標記。
     */
    public List<DriverPortalResponse.RouteMapResponse> getMap(Long driverId, LocalDate date) {
        findDriver(driverId);
        List<OrdersEntity> orders = findOrders(driverId, date, date);
        Map<Long, StoresEntity> stores = storesById(orders);
        Map<Long, WarehousesEntity> warehouses = warehousesById(orders);
        Map<Long, VehiclesEntity> vehicles = vehiclesById(orders);

        Map<Long, List<OrdersEntity>> ordersByRoute = new LinkedHashMap<>();
        for (OrdersEntity order : orders) {
            // 尚未建立路線的舊訂單，以單一群組回傳，仍可在地圖顯示門市位置。
            Long routeId = order.getRouteId() == null ? 0L : order.getRouteId();
            ordersByRoute.computeIfAbsent(routeId, ignored -> new ArrayList<>()).add(order);
        }

        List<DriverPortalResponse.RouteMapResponse> response = new ArrayList<>();
        for (Map.Entry<Long, List<OrdersEntity>> entry : ordersByRoute.entrySet()) {
            List<OrdersEntity> routeOrders = entry.getValue();
            OrdersEntity firstOrder = routeOrders.getFirst();
            WarehousesEntity warehouse = warehouses.get(firstOrder.getWarehouseId());
            VehiclesEntity vehicle = vehicles.get(firstOrder.getAssignedVehicleId());

            List<DriverPortalResponse.MapStopResponse> stops = routeOrders.stream()
                    .map(order -> toMapStopResponse(order, stores.get(order.getStoreId())))
                    .toList();

            response.add(new DriverPortalResponse.RouteMapResponse(
                    entry.getKey() == 0L ? null : entry.getKey(),
                    vehicle == null ? null : vehicle.getPlateNumber(),
                    toMapPlaceResponse(warehouse),
                    stops));
        }
        return response;
    }

    /**
     * 以指定日期區間的已指派訂單，產生司機的每日班表摘要。
     * 系統目前沒有獨立的班表資料表，因此班表以任務日期和司機固定工時組成。
     */
    public List<DriverPortalResponse.ScheduleDayResponse> getSchedule(
            Long driverId,
            LocalDate from,
            LocalDate to) {
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("班表起始日期不可晚於結束日期");
        }

        DriversEntity driver = findDriver(driverId);
        List<OrdersEntity> orders = findOrders(driverId, from, to);
        // LinkedHashMap 保留 findOrders 已排序的日期順序，前端可直接依序顯示。
        Map<LocalDate, List<OrdersEntity>> ordersByDate = new LinkedHashMap<>();
        for (OrdersEntity order : orders) {
            ordersByDate.computeIfAbsent(order.getDeliveryDate(), ignored -> new ArrayList<>()).add(order);
        }

        return ordersByDate.entrySet().stream()
                .map(entry -> new DriverPortalResponse.ScheduleDayResponse(
                        entry.getKey(),
                        driver.getWorkStart(),
                        driver.getWorkEnd(),
                        driver.getRestDuration(),
                        entry.getValue().size(),
                        entry.getValue().stream().map(OrdersEntity::getStatus).distinct().toList()))
                .toList();
    }

    /**
     * 為了維持本次只新增司機端分層，不變更 OrdersDAO，
     * 暫時以既有 findAll() 依司機與日期篩選。資料量增加後可再改成 DAO 衍生查詢。
     */
    private List<OrdersEntity> findOrders(Long driverId, LocalDate from, LocalDate to) {
        return ordersDAO.findAll().stream()
                .filter(order -> driverId.equals(order.getAssignedDriverId()))
                .filter(order -> !order.getDeliveryDate().isBefore(from)
                        && !order.getDeliveryDate().isAfter(to))
                .sorted(Comparator.comparing(OrdersEntity::getDeliveryDate)
                        .thenComparing(OrdersEntity::getSequence,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    /**
     * 批次載入訂單所屬門市，並轉成 id 對 Entity 的 Map，
     * 讓後續組 DTO 時能以 O(1) 取得門市資料。
     */
    private Map<Long, StoresEntity> storesById(List<OrdersEntity> orders) {
        return storesDAO.findAllById(orders.stream().map(OrdersEntity::getStoreId).toList()).stream()
                .collect(Collectors.toMap(StoresEntity::getId, Function.identity()));
    }

    /**
     * 批次載入路線起訖的倉庫資料，供地圖頁回傳倉庫座標。
     */
    private Map<Long, WarehousesEntity> warehousesById(List<OrdersEntity> orders) {
        return warehousesDAO.findAllById(orders.stream().map(OrdersEntity::getWarehouseId).toList()).stream()
                .collect(Collectors.toMap(WarehousesEntity::getId, Function.identity()));
    }

    /**
     * 批次載入已指派車輛；尚未指派車輛的訂單會先排除，避免以 null 查詢。
     */
    private Map<Long, VehiclesEntity> vehiclesById(List<OrdersEntity> orders) {
        return vehiclesDAO.findAllById(orders.stream()
                        .map(OrdersEntity::getAssignedVehicleId)
                        .filter(vehicleId -> vehicleId != null)
                        .toList())
                .stream()
                .collect(Collectors.toMap(VehiclesEntity::getId, Function.identity()));
    }

    /**
     * 統一處理司機不存在的情況，避免各公開方法重複撰寫查詢與例外邏輯。
     */
    private DriversEntity findDriver(Long driverId) {
        return driversDAO.findById(driverId)
                .orElseThrow(() -> new EntityNotFoundException("找不到司機，ID：" + driverId));
    }

    /**
     * 將訂單、門市與車輛資料整合成任務頁專用 DTO。
     * 關聯資料遺失時回傳 null，讓前端仍能顯示該筆訂單基本資訊。
     */
    private DriverPortalResponse.TaskResponse toTaskResponse(
            OrdersEntity order,
            StoresEntity store,
            VehiclesEntity vehicle) {
        return new DriverPortalResponse.TaskResponse(
                order.getId(),
                order.getOrderNumber(),
                order.getRouteId(),
                order.getSequence(),
                order.getStatus(),
                order.getBoxCount(),
                order.getItemDescription(),
                order.getNotes(),
                vehicle == null ? null : vehicle.getPlateNumber(),
                store == null ? null : store.getStoreCode(),
                store == null ? null : store.getName(),
                store == null ? null : store.getAddress(),
                store == null ? null : store.getContactName(),
                store == null ? null : store.getPhone(),
                store == null ? null : store.getReceivingStart(),
                store == null ? null : store.getReceivingEnd());
    }

    /**
     * 將倉庫轉成地圖座標 DTO；若倉庫資料不存在則回傳 null。
     */
    private DriverPortalResponse.MapPlaceResponse toMapPlaceResponse(WarehousesEntity warehouse) {
        if (warehouse == null) {
            return null;
        }
        return new DriverPortalResponse.MapPlaceResponse(
                warehouse.getName(),
                warehouse.getAddress(),
                warehouse.getLat(),
                warehouse.getLng());
    }

    /**
     * 將一筆訂單與對應門市轉成地圖上的停靠點資料。
     */
    private DriverPortalResponse.MapStopResponse toMapStopResponse(
            OrdersEntity order,
            StoresEntity store) {
        return new DriverPortalResponse.MapStopResponse(
                order.getId(),
                order.getSequence(),
                order.getOrderNumber(),
                order.getStatus(),
                store == null ? null : store.getName(),
                store == null ? null : store.getAddress(),
                store == null ? null : store.getLat(),
                store == null ? null : store.getLng());
    }
}
