package com.example.backend.service;

import com.example.backend.constants.OrderStatus;
import com.example.backend.constants.VehicleStatus;
import com.example.backend.dao.*;
import com.example.backend.dispatch.OsrmClient;
import com.example.backend.dispatch.RouteOptimizer;
import com.example.backend.dispatch.RouteResult;
import com.example.backend.dto.respones.DispatchResponse;
import com.example.backend.entity.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Transactional
public class DispatchService {
    private final OrdersDAO ordersDAO;
    private final VehiclesDAO vehiclesDAO;
    private final WarehousesDAO warehousesDAO;
    private final StoresDAO storesDAO;
    private final RoutesDAO routesDAO;
    private final OsrmClient osrmClient;
    private final RouteOptimizer routeOptimizer;

    public DispatchService(OrdersDAO ordersDAO,
                           VehiclesDAO vehiclesDAO,
                           WarehousesDAO warehousesDAO,
                           StoresDAO storesDAO,
                           RoutesDAO routesDAO,
                           OsrmClient osrmClient,
                           RouteOptimizer routeOptimizer) {
        this.ordersDAO = ordersDAO;
        this.vehiclesDAO = vehiclesDAO;
        this.warehousesDAO = warehousesDAO;
        this.storesDAO = storesDAO;
        this.routesDAO = routesDAO;
        this.osrmClient = osrmClient;
        this.routeOptimizer = routeOptimizer;
    }

    public DispatchResponse optimize(LocalDate date, Long warehouseId, List<Long> vehicleIds) {
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

                DispatchResponse.StopResponse stop = new DispatchResponse.StopResponse();
                stop.setOrderId(order.getId());
                stop.setStoreId(order.getStoreId());
                stop.setSequence(sequence);
                stops.add(stop);
                loadedBoxes += order.getBoxCount();
                sequence++;
            }
            double loadRate = (double) loadedBoxes / vehicle.getCapacity();
            saveRoute.setLoadRate(loadRate);

            DispatchResponse.RouteResponse routeResponse = new DispatchResponse.RouteResponse();
            routeResponse.setVehicleId(vehicle.getId());
            routeResponse.setStops(stops);
            routeResponse.setTotalDistance((double) vr.getDistance());
            routeResponse.setLoadRate(loadRate);
            routeResponses.add(routeResponse);
        }
        List<Long> unassignedOrderIds = new ArrayList<>();
        for (Integer node : result.getDroppedNodes()) {
            unassignedOrderIds.add(orders.get(node - 1).getId());
        }
        DispatchResponse dispatchResponse = new DispatchResponse();
        dispatchResponse.setRoutes(routeResponses);
        dispatchResponse.setUnassignedOrderIds(unassignedOrderIds);


        return dispatchResponse;
    }
}
