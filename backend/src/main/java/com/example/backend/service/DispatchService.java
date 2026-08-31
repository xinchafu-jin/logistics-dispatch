package com.example.backend.service;

import com.example.backend.constants.OrderStatus;
import com.example.backend.constants.RouteStatus;
import com.example.backend.constants.VehicleStatus;
import com.example.backend.dao.*;
import com.example.backend.dispatch.OsrmClient;
import com.example.backend.dispatch.RouteOptimizer;
import com.example.backend.dispatch.RouteResult;
import com.example.backend.dto.request.ReassignDTO;
import com.example.backend.dto.respones.DispatchResponse;
import com.example.backend.entity.*;
import com.google.protobuf.OptionOrBuilder;
import jakarta.validation.Valid;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.RouteMatcher;

import javax.swing.text.html.Option;
import java.time.LocalDate;
import java.util.*;

@Service
@Transactional
public class DispatchService {
    private final OrdersDAO ordersDAO;
    private final VehiclesDAO vehiclesDAO;
    private final WarehousesDAO warehousesDAO;
    private final StoresDAO storesDAO;
    private final RoutesDAO routesDAO;
    private final DriversDAO driversDAO;
    private final OsrmClient osrmClient;
    private final RouteOptimizer routeOptimizer;

    public DispatchService(OrdersDAO ordersDAO,
                           VehiclesDAO vehiclesDAO,
                           WarehousesDAO warehousesDAO,
                           StoresDAO storesDAO,
                           RoutesDAO routesDAO,
                           DriversDAO driversDAO,
                           OsrmClient osrmClient,
                           RouteOptimizer routeOptimizer) {
        this.ordersDAO = ordersDAO;
        this.vehiclesDAO = vehiclesDAO;
        this.warehousesDAO = warehousesDAO;
        this.storesDAO = storesDAO;
        this.routesDAO = routesDAO;
        this.driversDAO = driversDAO;
        this.osrmClient = osrmClient;
        this.routeOptimizer = routeOptimizer;
    }

    public DispatchResponse optimize(LocalDate date, Long warehouseId, List<Long> vehicleIds) {
        WarehousesEntity warehousesEntity =
                warehousesDAO.findById(warehouseId).
                        orElseThrow(() -> new IllegalArgumentException("查無倉庫" + warehouseId));
        clearExistingDraftRoutes(date, warehouseId);
        // 撈當日訂單 已確認之狀態
        List<OrdersEntity> orders =
                ordersDAO.findByDeliveryDateAndStatusAndWarehouseId(
                        date, OrderStatus.CONFIRMED, warehouseId);
        if (orders.isEmpty()) {
            throw new IllegalArgumentException("當天無已確認的訂單：" + date);
        }
        // 未指定車輛時，自動取該倉所有可用車當候選車池，交給 OR-Tools 決定實際出幾台
        List<VehiclesEntity> vehicles;
        if (vehicleIds == null || vehicleIds.isEmpty()) {
            vehicles = vehiclesDAO.findByWarehouseIdAndStatus(warehouseId, VehicleStatus.AVAILABLE);
            if (vehicles.isEmpty()) {
                throw new IllegalArgumentException("倉庫 " + warehouseId + " 目前沒有可用車輛");
            }
        } else {
            vehicles = vehiclesDAO.findAllById(vehicleIds);
            if (vehicles.size() != vehicleIds.size()) {
                throw new IllegalArgumentException("查無車輛");
            }
            for (VehiclesEntity v : vehicles) {
                if (!warehouseId.equals(v.getWarehouseId())) {
                    throw new IllegalArgumentException("車輛 " + v.getPlateNumber() + " 不屬於倉庫 " + warehouseId);
                }
                if (v.getStatus() != VehicleStatus.AVAILABLE) {
                    throw new IllegalArgumentException(
                            "車輛 " + v.getPlateNumber() + " 目前狀態為 " + v.getStatus() + "，無法排入路線");
                }
            }
        }

        // 撈商店id
        List<Long> storeIds = new ArrayList<>();
        for (OrdersEntity item : orders) {
            storeIds.add(item.getStoreId());
        }
        Map<Long, StoresEntity> storesMap = new HashMap<>();
        for (StoresEntity store : storesDAO.findAllById(storeIds)) {
            storesMap.put(store.getId(), store);
        }
        List<double[]> locations = new ArrayList<>();
        locations.add(new double[]{warehousesEntity.getLng(), warehousesEntity.getLat()});
        for (OrdersEntity order : orders) {
            StoresEntity store = storesMap.get(order.getStoreId());
            if (store == null) {
                throw new IllegalStateException("訂單 " + order.getId() + " 找不到門市：" + order.getStoreId());
            }
            locations.add(new double[]{store.getLng(), store.getLat()});
        }

        long[] demands = new long[locations.size()];
        for (int i = 0; i < orders.size(); i++) {
            demands[i + 1] = orders.get(i).getBoxCount();
        }
        long[] vehicleCapacities = new long[vehicles.size()];
        for (int i = 0; i < vehicles.size(); i++) {
            vehicleCapacities[i] = vehicles.get(i).getCapacity();

        }

        long[][] matrix = osrmClient.table(locations);
        RouteResult result = routeOptimizer.solve(matrix, demands, vehicleCapacities, 0);
        if (result == null) {
            throw new IllegalStateException("請檢查車輛容量是否足夠");
        }
        List<DispatchResponse.RouteResponse> routeResponses = new ArrayList<>();
        for (RouteResult.VehicleRoute vr : result.getVehicleRoutes()) {
            List<Integer> nodeSequence = vr.getNodeSequence();
            if (nodeSequence.size() <= 2) {
                continue;
            }
            VehiclesEntity vehicle = vehicles.get(
                    vr.getVehicleIndex()
            );
            RoutesEntity route = new RoutesEntity();
            route.setDate(date);
            route.setWarehouseId(warehouseId);
            route.setVehicleId(vehicle.getId());
            route.setTotalDistance(
                    (double) vr.getDistance());
            RoutesEntity saveRoute = routesDAO.save(route);
            List<DispatchResponse.StopResponse> stops = new ArrayList<>();
            int sequence = 1;
            int loadedBoxes = 0;
            for (int i = 1; i < nodeSequence.size() - 1; i++) {
                int node = nodeSequence.get(i);
                OrdersEntity order = orders.get(node - 1);
                order.setRouteId(saveRoute.getId());
                order.setSequence(sequence);
                order.setAssignedVehicleId(vehicle.getId());
                order.setStatus(OrderStatus.SCHEDULED);
                ordersDAO.save(order);

                stops.add(toStop(order, storesMap.get(order.getStoreId()), sequence));
                loadedBoxes += order.getBoxCount();
                sequence++;
            }
            double loadRate = (double) loadedBoxes / vehicle.getCapacity();
            saveRoute.setLoadRate(loadRate);

            DispatchResponse.RouteResponse routeResponse = new DispatchResponse.RouteResponse();
            routeResponse.setRouteId(saveRoute.getId());
            routeResponse.setVehicleId(vehicle.getId());
            routeResponse.setPlateNumber(vehicle.getPlateNumber());
            routeResponse.setVehicleType(vehicle.getVehicleType());
            routeResponse.setCapacity(vehicle.getCapacity());
            routeResponse.setStops(stops);
            routeResponse.setStopCount(stops.size());
            routeResponse.setLoadedBoxes(loadedBoxes);
            routeResponse.setTotalDistance((double) vr.getDistance());
            routeResponse.setLoadRate(loadRate);
            routeResponses.add(routeResponse);
        }
        List<DispatchResponse.UnassignedOrderResponse> unassignedOrders = new ArrayList<>();
        for (Integer node : result.getDroppedNodes()) {
            OrdersEntity order = orders.get(node - 1);
            unassignedOrders.add(toUnassigned(order, storesMap.get(order.getStoreId())));
        }
        DispatchResponse dispatchResponse = new DispatchResponse();
        dispatchResponse.setDate(date);
        dispatchResponse.setWarehouse(toWarehouse(warehousesEntity));
        dispatchResponse.setRoutes(routeResponses);
        dispatchResponse.setUnassignedOrders(unassignedOrders);

        return dispatchResponse;
    }

    public DispatchResponse reassign(ReassignDTO dto) {
        LocalDate date = dto.getDate();
        Optional<WarehousesEntity> opt = warehousesDAO.findById(dto.getWarehouseId());
        if (!opt.isPresent()) {
            throw new IllegalArgumentException("查無倉庫" + dto.getWarehouseId());
        }
        WarehousesEntity warehousesEntity = opt.get();
        Long warehouseId = dto.getWarehouseId();

        // 當天該倉的全部訂單，不分狀態。驗證與後續綁定都從這裡取，避免逐筆查。
        Map<Long, OrdersEntity> ordersMap = new HashMap<>();
        for (OrdersEntity order : ordersDAO.findByDeliveryDateAndWarehouseId(date, warehouseId)) {
            ordersMap.put(order.getId(), order);
        }

        // ── 訂單驗證：存在、狀態可排、沒有被重複指派 ──
        Map<Long, Long> orderToVehicle = new HashMap<>();
        for (ReassignDTO.RouteAssignment ra : dto.getRoutes()) {
            for (Long orderId : ra.getOrderIds()) {
                OrdersEntity order = ordersMap.get(orderId);
                if (order == null) {
                    // 這張單不在當天這個倉裡，沒有編號可顯示，只能給 id
                    throw new IllegalArgumentException(
                            "訂單 " + orderId + " 不屬於 " + date + " 的倉庫 " + warehouseId);
                }
                if (order.getStatus() != OrderStatus.CONFIRMED
                        && order.getStatus() != OrderStatus.SCHEDULED) {
                    throw new IllegalArgumentException(
                            "訂單 " + order.getOrderNumber() + " 狀態為 " + order.getStatus()
                                    + "，無法排入路線");
                }
                Long previous = orderToVehicle.putIfAbsent(orderId, ra.getVehicleId());
                if (previous != null) {
                    throw new IllegalArgumentException("訂單 " + order.getOrderNumber()
                            + " 被重複指派給 " + plateNumberOf(previous)
                            + " 和 " + plateNumberOf(ra.getVehicleId()));
                }
            }
        }

        // ── 車輛驗證：不重複、屬於該倉、狀態可用 ──
        List<Long> vehicleIds = new ArrayList<>();
        for (ReassignDTO.RouteAssignment ra : dto.getRoutes()) {
            if (vehicleIds.contains(ra.getVehicleId())) {
                throw new IllegalArgumentException(
                        "車輛 " + plateNumberOf(ra.getVehicleId()) + " 出現在多條路線");
            }
            vehicleIds.add(ra.getVehicleId());
        }
        Map<Long, VehiclesEntity> vehiclesMap = new HashMap<>();
        for (VehiclesEntity vehicle : vehiclesDAO.findAllById(vehicleIds)) {
            vehiclesMap.put(vehicle.getId(), vehicle);
        }
        for (Long vehicleId : vehicleIds) {
            VehiclesEntity vehicle = vehiclesMap.get(vehicleId);
            if (vehicle == null) {
                throw new IllegalArgumentException("查無車輛：" + vehicleId);
            }
            if (!warehouseId.equals(vehicle.getWarehouseId())) {
                throw new IllegalArgumentException(
                        "車輛 " + vehicle.getPlateNumber() + " 不屬於倉庫 " + warehouseId);
            }
            if (vehicle.getStatus() != VehicleStatus.AVAILABLE) {
                throw new IllegalArgumentException("車輛 " + vehicle.getPlateNumber()
                        + " 目前狀態為 " + vehicle.getStatus() + "，無法排入路線");
            }
        }
        // ── 司機驗證：不重複、在職 ──
        List<Long> driverIds = new ArrayList<>();
        for (ReassignDTO.RouteAssignment ra : dto.getRoutes()) {
            // 未指派是合法的：草稿階段可以先排車後派人，發布前才要求一定要有
            if (ra.getDriverId() == null) {
                continue;
            }
            if (driverIds.contains(ra.getDriverId())) {
                throw new IllegalArgumentException(
                        "司機 " + driverNameOf(ra.getDriverId()) + " 被指派給多台車");
            }
            driverIds.add(ra.getDriverId());
        }
        Map<Long, DriversEntity> driversMap = new HashMap<>();
        for (DriversEntity driver : driversDAO.findAllById(driverIds)) {
            driversMap.put(driver.getId(), driver);
        }
        for (Long driverId : driverIds) {
            DriversEntity driver = driversMap.get(driverId);
            if (driver == null) {
                throw new IllegalArgumentException("查無司機：" + driverId);
            }
            if (!Boolean.TRUE.equals(driver.getIsActive())) {
                throw new IllegalArgumentException(
                        "司機 " + driver.getName() + " 目前非在職狀態，無法指派");
            }
        }
        // 司機不綁倉庫，但一天只開一條路線，所以要查整天而不是只查這個倉。
        // 本倉的草稿等一下就會被 clearExistingDraftRoutes 清掉，不算佔用。
        for (RoutesEntity other : routesDAO.findByDateAndDriverIdIsNotNull(date)) {
            if (warehouseId.equals(other.getWarehouseId())) {
                continue;
            }
            if (driverIds.contains(other.getDriverId())) {
                throw new IllegalArgumentException(
                        "司機 " + driverNameOf(other.getDriverId()) + " 當天已排在 "
                                + plateNumberOf(other.getVehicleId()) + "，無法重複指派");
            }
        }

        // ══ 驗證到此結束，以下開始改資料 ══

        // 清掉當天既有草稿並解綁訂單；沒被重新指派的訂單就自動留在未排入池
        clearExistingDraftRoutes(date, warehouseId);

        // 距離矩陣的索引：0 是倉庫，其後依訂單在請求中出現的順序排列
        List<OrdersEntity> assignedOrders = new ArrayList<>();
        Map<Long, Integer> locationIndex = new HashMap<>();
        for (ReassignDTO.RouteAssignment ra : dto.getRoutes()) {
            for (Long orderId : ra.getOrderIds()) {
                locationIndex.put(orderId, assignedOrders.size() + 1);
                assignedOrders.add(ordersMap.get(orderId));
            }
        }

        List<Long> storeIds = new ArrayList<>();
        for (OrdersEntity order : assignedOrders) {
            storeIds.add(order.getStoreId());
        }
        Map<Long, StoresEntity> storesMap = new HashMap<>();
        for (StoresEntity store : storesDAO.findAllById(storeIds)) {
            storesMap.put(store.getId(), store);
        }

        List<double[]> locations = new ArrayList<>();
        locations.add(new double[]{warehousesEntity.getLng(), warehousesEntity.getLat()});
        for (OrdersEntity order : assignedOrders) {
            StoresEntity store = storesMap.get(order.getStoreId());
            if (store == null) {
                throw new IllegalStateException(
                        "訂單 " + order.getId() + " 找不到門市：" + order.getStoreId());
            }
            locations.add(new double[]{store.getLng(), store.getLat()});
        }

        // 一次算完整矩陣，各路線再依索引累加相鄰兩點的距離
        long[][] matrix = osrmClient.table(locations);

        for (ReassignDTO.RouteAssignment ra : dto.getRoutes()) {
            VehiclesEntity vehicle = vehiclesMap.get(ra.getVehicleId());

            // 一律重建路線，不沿用 dto 的 routeId —— 上面已經把當天草稿整批清掉了
            RoutesEntity route = new RoutesEntity();
            route.setDate(date);
            route.setWarehouseId(warehouseId);
            route.setVehicleId(vehicle.getId());
            // 未指派時是 null，正好對上 uk_routes_date_driver（NULL 不參與唯一性比對）
            route.setDriverId(ra.getDriverId());
            RoutesEntity saveRoute = routesDAO.save(route);

            int sequence = 1;
            int loadedBoxes = 0;
            long distance = 0;
            int previousIndex = 0; // 從倉庫出發
            for (Long orderId : ra.getOrderIds()) {
                OrdersEntity order = ordersMap.get(orderId);
                order.setRouteId(saveRoute.getId());
                order.setSequence(sequence);
                order.setAssignedVehicleId(vehicle.getId());
                // 跟著路線一起寫。司機端要從訂單查任務，這欄留 null 的話會落空
                order.setAssignedDriverId(ra.getDriverId());
                order.setStatus(OrderStatus.SCHEDULED);
                ordersDAO.save(order);

                int currentIndex = locationIndex.get(orderId);
                distance += matrix[previousIndex][currentIndex];
                previousIndex = currentIndex;

                loadedBoxes += order.getBoxCount();
                sequence++;
            }
            distance += matrix[previousIndex][0]; // 回倉庫

            saveRoute.setTotalDistance((double) distance);
            saveRoute.setLoadRate((double) loadedBoxes / vehicle.getCapacity());
        }

        return getBoard(date, warehouseId);
    }

    @Transactional(readOnly = true)
    public DispatchResponse getBoard(LocalDate date, Long warehouseId) {
        WarehousesEntity warehouse =
                warehousesDAO.findById(warehouseId)
                        .orElseThrow(() -> new IllegalArgumentException("查無倉庫" + warehouseId));

        List<RoutesEntity> routes = routesDAO.findByDateAndWarehouseId(date, warehouseId);
        List<OrdersEntity> unassigned =
                ordersDAO.findByDeliveryDateAndWarehouseIdAndRouteIdIsNull(date, warehouseId);

        // 一次把畫面要用到的門市 / 車輛 / 司機撈齊，避免在迴圈裡逐筆查（N+1）
        Map<Long, List<OrdersEntity>> ordersByRoute = new HashMap<>();
        List<Long> storeIds = new ArrayList<>();
        List<Long> vehicleIds = new ArrayList<>();
        List<Long> driverIds = new ArrayList<>();
        for (RoutesEntity route : routes) {
            List<OrdersEntity> routeOrders = ordersDAO.findByRouteIdOrderBySequence(route.getId());
            ordersByRoute.put(route.getId(), routeOrders);
            for (OrdersEntity order : routeOrders) {
                storeIds.add(order.getStoreId());
            }
            vehicleIds.add(route.getVehicleId());
            if (route.getDriverId() != null) {
                driverIds.add(route.getDriverId());
            }
        }
        for (OrdersEntity order : unassigned) {
            storeIds.add(order.getStoreId());
        }

        Map<Long, StoresEntity> storesMap = new HashMap<>();
        for (StoresEntity store : storesDAO.findAllById(storeIds)) {
            storesMap.put(store.getId(), store);
        }
        Map<Long, VehiclesEntity> vehiclesMap = new HashMap<>();
        for (VehiclesEntity vehicle : vehiclesDAO.findAllById(vehicleIds)) {
            vehiclesMap.put(vehicle.getId(), vehicle);
        }
        Map<Long, DriversEntity> driversMap = new HashMap<>();
        for (DriversEntity driver : driversDAO.findAllById(driverIds)) {
            driversMap.put(driver.getId(), driver);
        }

        List<DispatchResponse.RouteResponse> routeResponses = new ArrayList<>();
        for (RoutesEntity route : routes) {
            List<DispatchResponse.StopResponse> stops = new ArrayList<>();
            int loadedBoxes = 0;
            for (OrdersEntity order : ordersByRoute.get(route.getId())) {
                stops.add(toStop(order, storesMap.get(order.getStoreId()), order.getSequence()));
                loadedBoxes += order.getBoxCount();
            }

            DispatchResponse.RouteResponse routeResponse = new DispatchResponse.RouteResponse();
            routeResponse.setRouteId(route.getId());
            routeResponse.setVehicleId(route.getVehicleId());
            VehiclesEntity vehicle = vehiclesMap.get(route.getVehicleId());
            if (vehicle != null) {
                routeResponse.setPlateNumber(vehicle.getPlateNumber());
                routeResponse.setVehicleType(vehicle.getVehicleType());
                routeResponse.setCapacity(vehicle.getCapacity());
            }
            routeResponse.setDriverId(route.getDriverId());
            DriversEntity driver = driversMap.get(route.getDriverId());
            if (driver != null) {
                routeResponse.setDriverName(driver.getName());
            }
            routeResponse.setStops(stops);
            routeResponse.setStopCount(stops.size());
            routeResponse.setLoadedBoxes(loadedBoxes);
            routeResponse.setTotalDistance(route.getTotalDistance());
            routeResponse.setEstimatedFuelCost(route.getEstimatedFuelCost());
            routeResponse.setEstimatedWorkMinutes(route.getEstimatedWorkMinutes());
            routeResponse.setLoadRate(route.getLoadRate());
            routeResponses.add(routeResponse);
        }

        List<DispatchResponse.UnassignedOrderResponse> unassignedOrders = new ArrayList<>();
        for (OrdersEntity order : unassigned) {
            unassignedOrders.add(toUnassigned(order, storesMap.get(order.getStoreId())));
        }

        DispatchResponse response = new DispatchResponse();
        response.setDate(date);
        response.setWarehouse(toWarehouse(warehouse));
        response.setRoutes(routeResponses);
        response.setUnassignedOrders(unassignedOrders);
        response.setDriversTakenElsewhere(driversTakenElsewhere(date, warehouseId));
        return response;
    }

    /**
     * 當天已被「其他倉庫」排走的司機。
     *
     * 司機不綁倉庫（見 docs/data-model.md），但一位司機一天只開一條路線，
     * 而看板是按倉切的 —— 只看當前倉的話，會把別倉用掉的司機也列成可選。
     */
    private List<DispatchResponse.DriverTakenResponse> driversTakenElsewhere(
            LocalDate date, Long warehouseId) {

        List<RoutesEntity> others = new ArrayList<>();
        for (RoutesEntity route : routesDAO.findByDateAndDriverIdIsNotNull(date)) {
            if (!warehouseId.equals(route.getWarehouseId())) {
                others.add(route);
            }
        }
        if (others.isEmpty()) {
            return new ArrayList<>();
        }

        // 同樣先收集 id 再一次撈，不要在迴圈裡逐筆查
        List<Long> driverIds = new ArrayList<>();
        List<Long> vehicleIds = new ArrayList<>();
        List<Long> warehouseIds = new ArrayList<>();
        for (RoutesEntity route : others) {
            driverIds.add(route.getDriverId());
            vehicleIds.add(route.getVehicleId());
            warehouseIds.add(route.getWarehouseId());
        }
        Map<Long, DriversEntity> driversMap = new HashMap<>();
        for (DriversEntity driver : driversDAO.findAllById(driverIds)) {
            driversMap.put(driver.getId(), driver);
        }
        Map<Long, VehiclesEntity> vehiclesMap = new HashMap<>();
        for (VehiclesEntity vehicle : vehiclesDAO.findAllById(vehicleIds)) {
            vehiclesMap.put(vehicle.getId(), vehicle);
        }
        Map<Long, WarehousesEntity> warehousesMap = new HashMap<>();
        for (WarehousesEntity item : warehousesDAO.findAllById(warehouseIds)) {
            warehousesMap.put(item.getId(), item);
        }

        List<DispatchResponse.DriverTakenResponse> taken = new ArrayList<>();
        for (RoutesEntity route : others) {
            DispatchResponse.DriverTakenResponse item = new DispatchResponse.DriverTakenResponse();
            item.setDriverId(route.getDriverId());
            DriversEntity driver = driversMap.get(route.getDriverId());
            if (driver != null) {
                item.setDriverName(driver.getName());
            }
            VehiclesEntity vehicle = vehiclesMap.get(route.getVehicleId());
            if (vehicle != null) {
                item.setPlateNumber(vehicle.getPlateNumber());
            }
            WarehousesEntity itemWarehouse = warehousesMap.get(route.getWarehouseId());
            if (itemWarehouse != null) {
                item.setWarehouseName(itemWarehouse.getName());
            }
            taken.add(item);
        }
        return taken;
    }

    private void clearExistingDraftRoutes(LocalDate date, Long warehouseId) {

        List<RoutesEntity> existing = routesDAO.findByDateAndWarehouseId(date, warehouseId);
        List<String> publishedPlates = new ArrayList<>();
        List<Long> draftRouteIds = new ArrayList<>();
        if (existing.isEmpty()) {
            return;
        }
        for (RoutesEntity item : existing) {
            if (item.getStatus() == RouteStatus.PUBLISHED) {
                publishedPlates.add(plateNumberOf(item.getVehicleId()));
            } else {
                draftRouteIds.add(item.getId());
            }

        }
        if (!publishedPlates.isEmpty()) {
            throw new IllegalArgumentException(
                    "當天已有發布的排班（" + String.join("、", publishedPlates) + "），請先撤回再重排");
        }
        List<OrdersEntity> boundOrders = ordersDAO.findByRouteIdIn(draftRouteIds);
        for (OrdersEntity order : boundOrders) {
            order.setRouteId(null);
            order.setSequence(null);
            order.setAssignedVehicleId(null);
            order.setAssignedDriverId(null);
            if (order.getStatus() == OrderStatus.SCHEDULED) {
                order.setStatus(OrderStatus.CONFIRMED);
            }

        }
        ordersDAO.saveAll(boundOrders);
        ordersDAO.flush();
        routesDAO.deleteAllById(draftRouteIds);
        // 必須立刻送出 DELETE：Hibernate flush 時會先做 INSERT 再做 DELETE，
        // 不先清掉舊路線的話，新路線會撞上 uk_routes_date_vehicle 唯一鍵
        routesDAO.flush();
    }

    private String plateNumberOf(Long vehicleId) {
        Optional<VehiclesEntity> vehicle = vehiclesDAO.findById(vehicleId);
        if (vehicle.isPresent()) {
            return vehicle.get().getPlateNumber();
        }
        return "車牌" + vehicleId;
    }

    private DispatchResponse.WarehouseResponse toWarehouse(WarehousesEntity warehouse) {
        DispatchResponse.WarehouseResponse response = new DispatchResponse.WarehouseResponse();
        response.setId(warehouse.getId());
        response.setWarehouseCode(warehouse.getWarehouseCode());
        response.setName(warehouse.getName());
        response.setAddress(warehouse.getAddress());
        response.setLat(warehouse.getLat());
        response.setLng(warehouse.getLng());
        return response;
    }

    private DispatchResponse.StopResponse toStop(OrdersEntity order, StoresEntity store, Integer sequence) {
        DispatchResponse.StopResponse stop = new DispatchResponse.StopResponse();
        stop.setSequence(sequence);
        stop.setOrderId(order.getId());
        stop.setOrderNumber(order.getOrderNumber());
        stop.setBoxCount(order.getBoxCount());
        stop.setItemDescription(order.getItemDescription());
        stop.setStoreId(order.getStoreId());
        if (store != null) {
            stop.setStoreCode(store.getStoreCode());
            stop.setStoreName(store.getName());
            stop.setAddress(store.getAddress());
            stop.setLat(store.getLat());
            stop.setLng(store.getLng());
            stop.setContactName(store.getContactName());
            stop.setPhone(store.getPhone());
            stop.setReceivingStart(store.getReceivingStart());
            stop.setReceivingEnd(store.getReceivingEnd());
        }
        return stop;
    }

    private DispatchResponse.UnassignedOrderResponse toUnassigned(OrdersEntity order, StoresEntity store) {
        DispatchResponse.UnassignedOrderResponse response = new DispatchResponse.UnassignedOrderResponse();
        response.setOrderId(order.getId());
        response.setOrderNumber(order.getOrderNumber());
        response.setBoxCount(order.getBoxCount());
        response.setStoreId(order.getStoreId());
        if (store != null) {
            response.setStoreCode(store.getStoreCode());
            response.setStoreName(store.getName());
            response.setAddress(store.getAddress());
            response.setLat(store.getLat());
            response.setLng(store.getLng());
        }


        return response;
    }

    private String driverNameOf(Long driverId) {
        Optional<DriversEntity> driver = driversDAO.findById(driverId);
        return driver.map(DriversEntity::getName).orElse("司機" + driverId);
    }
}
