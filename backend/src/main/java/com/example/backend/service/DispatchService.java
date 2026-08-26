package com.example.backend.service;

import com.example.backend.constants.OrderStatus;
import com.example.backend.constants.RouteStatus;
import com.example.backend.constants.VehicleStatus;
import com.example.backend.dao.*;
import com.example.backend.dispatch.OsrmClient;
import com.example.backend.dispatch.RouteOptimizer;
import com.example.backend.dispatch.RouteResult;
import com.example.backend.dto.respones.DispatchResponse;
import com.example.backend.entity.*;
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
        clearExistingDraftRoutes(date,warehouseId);
        // 撈當日訂單 已確認之狀態
        List<OrdersEntity> orders =
                ordersDAO.findByDeliveryDateAndStatusAndWarehouseId(
                        date, OrderStatus.CONFIRMED, warehouseId);
        if (orders.isEmpty()) {
            throw new IllegalArgumentException("當天無已確認的訂單：" + date);
        }
        List<VehiclesEntity> vehicles =
                vehiclesDAO.findAllById(vehicleIds);
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
        WarehousesEntity warehousesEntity =
                warehousesDAO.findById(warehouseId).
                        orElseThrow(() -> new IllegalArgumentException("查無倉庫" + warehouseId));
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
        return response;
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
}
