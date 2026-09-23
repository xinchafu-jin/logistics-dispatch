package com.example.backend.service;

import com.example.backend.constants.OrderStatus;
import com.example.backend.constants.RouteStatus;
import com.example.backend.dao.DriversDAO;
import com.example.backend.dao.OrdersDAO;
import com.example.backend.dao.RoutesDAO;
import com.example.backend.dao.StoresDAO;
import com.example.backend.dao.VehiclesDAO;
import com.example.backend.dao.WarehousesDAO;
import com.example.backend.dto.respones.DispatchResponse;
import com.example.backend.entity.DriversEntity;
import com.example.backend.entity.OrdersEntity;
import com.example.backend.entity.RoutesEntity;
import com.example.backend.entity.StoresEntity;
import com.example.backend.entity.VehiclesEntity;
import com.example.backend.entity.WarehousesEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 調度看板的唯讀組裝服務。
 *
 * <p>完成、無人簽收、失敗與取消的訂單仍保留在原路線供歷史與報表使用，
 * 但不再回傳成可拖曳卡片。這個服務不執行排車，也不修改任何訂單。</p>
 */
@Service
@Transactional(readOnly = true)
public class DispatchBoardService {

    private static final Set<OrderStatus> VISIBLE_ROUTE_STATUSES =
            EnumSet.of(OrderStatus.CONFIRMED, OrderStatus.IN_DELIVERY);

    private final OrdersDAO ordersDAO;
    private final RoutesDAO routesDAO;
    private final StoresDAO storesDAO;
    private final VehiclesDAO vehiclesDAO;
    private final DriversDAO driversDAO;
    private final WarehousesDAO warehousesDAO;

    public DispatchBoardService(
            OrdersDAO ordersDAO,
            RoutesDAO routesDAO,
            StoresDAO storesDAO,
            VehiclesDAO vehiclesDAO,
            DriversDAO driversDAO,
            WarehousesDAO warehousesDAO
    ) {
        this.ordersDAO = ordersDAO;
        this.routesDAO = routesDAO;
        this.storesDAO = storesDAO;
        this.vehiclesDAO = vehiclesDAO;
        this.driversDAO = driversDAO;
        this.warehousesDAO = warehousesDAO;
    }

    public DispatchResponse getBoard(LocalDate date, Long warehouseId) {
        WarehousesEntity warehouse = warehousesDAO.findById(warehouseId)
                .orElseThrow(() -> new IllegalArgumentException("查無倉庫" + warehouseId));

        List<RoutesEntity> routes = routesDAO.findByDateAndWarehouseId(date, warehouseId);
        List<OrdersEntity> unassigned =
                ordersDAO.findByDeliveryDateAndStatusAndWarehouseIdAndRouteIdIsNull(
                        date, OrderStatus.CONFIRMED, warehouseId);

        Map<Long, List<OrdersEntity>> ordersByRoute = new HashMap<>();
        Map<Long, List<OrdersEntity>> allOrdersByRoute = new HashMap<>();
        Set<Long> storeIds = new LinkedHashSet<>();
        Set<Long> vehicleIds = new LinkedHashSet<>();
        Set<Long> driverIds = new LinkedHashSet<>();

        for (RoutesEntity route : routes) {
            List<OrdersEntity> allOrders = ordersDAO.findByRouteIdOrderBySequence(route.getId());
            List<OrdersEntity> visibleOrders = allOrders.stream()
                    .filter(order -> VISIBLE_ROUTE_STATUSES.contains(order.getStatus()))
                    .toList();
            allOrdersByRoute.put(route.getId(), allOrders);
            ordersByRoute.put(route.getId(), visibleOrders);
            for (OrdersEntity order : visibleOrders) {
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

        Map<Long, StoresEntity> stores = new HashMap<>();
        storesDAO.findAllById(storeIds).forEach(item -> stores.put(item.getId(), item));
        Map<Long, VehiclesEntity> vehicles = new HashMap<>();
        vehiclesDAO.findAllById(vehicleIds).forEach(item -> vehicles.put(item.getId(), item));
        Map<Long, DriversEntity> drivers = new HashMap<>();
        driversDAO.findAllById(driverIds).forEach(item -> drivers.put(item.getId(), item));

        List<DispatchResponse.RouteResponse> routeResponses = new ArrayList<>();
        for (RoutesEntity route : routes) {
            List<DispatchResponse.StopResponse> stops = new ArrayList<>();
            int remainingBoxes = 0;
            for (OrdersEntity order : ordersByRoute.getOrDefault(route.getId(), List.of())) {
                stops.add(toStop(order, stores.get(order.getStoreId()), route.getStatus()));
            }
            for (OrdersEntity order : allOrdersByRoute.getOrDefault(route.getId(), List.of())) {
                if (order.getStatus() != OrderStatus.COMPLETED
                        && order.getStatus() != OrderStatus.CANCELLED) {
                    remainingBoxes += order.getBoxCount();
                }
            }

            DispatchResponse.RouteResponse response = new DispatchResponse.RouteResponse();
            response.setRouteId(route.getId());
            response.setStatus(route.getStatus());
            response.setVehicleId(route.getVehicleId());
            VehiclesEntity vehicle = vehicles.get(route.getVehicleId());
            if (vehicle != null) {
                response.setPlateNumber(vehicle.getPlateNumber());
                response.setVehicleType(vehicle.getVehicleType());
                response.setCapacity(vehicle.getCapacity());
            }
            response.setDriverId(route.getDriverId());
            DriversEntity driver = drivers.get(route.getDriverId());
            if (driver != null) {
                response.setDriverName(driver.getName());
            }
            response.setStops(stops);
            response.setStopCount(stops.size());
            response.setLoadedBoxes(remainingBoxes);
            response.setTotalDistance(route.getTotalDistance());
            response.setEstimatedFuelCost(route.getEstimatedFuelCost());
            response.setEstimatedWorkMinutes(route.getEstimatedWorkMinutes());
            response.setLoadRate(response.getCapacity() != null && response.getCapacity() > 0
                    ? (double) remainingBoxes / response.getCapacity()
                    : 0D);
            routeResponses.add(response);
        }

        List<DispatchResponse.UnassignedOrderResponse> unassignedResponses = new ArrayList<>();
        for (OrdersEntity order : unassigned) {
            unassignedResponses.add(toUnassigned(order, stores.get(order.getStoreId())));
        }

        DispatchResponse result = new DispatchResponse();
        result.setDate(date);
        result.setWarehouse(toWarehouse(warehouse));
        result.setRoutes(routeResponses);
        result.setUnassignedOrders(unassignedResponses);
        result.setDriversTakenElsewhere(driversTakenElsewhere(date, warehouseId));
        return result;
    }

    public List<DispatchResponse> getBoards(LocalDate date) {
        Set<Long> warehouseIds = new LinkedHashSet<>();
        for (RoutesEntity route : routesDAO.findByDate(date)) {
            warehouseIds.add(route.getWarehouseId());
        }
        List<DispatchResponse> responses = new ArrayList<>();
        for (Long warehouseId : warehouseIds) {
            responses.add(getBoard(date, warehouseId));
        }
        return responses;
    }

    private List<DispatchResponse.DriverTakenResponse> driversTakenElsewhere(
            LocalDate date,
            Long warehouseId
    ) {
        List<RoutesEntity> otherRoutes = routesDAO.findByDateAndDriverIdIsNotNull(date).stream()
                .filter(route -> !warehouseId.equals(route.getWarehouseId()))
                .filter(route -> !ordersDAO.findByRouteIdAndStatusInOrderBySequence(
                        route.getId(), VISIBLE_ROUTE_STATUSES).isEmpty())
                .toList();
        if (otherRoutes.isEmpty()) {
            return new ArrayList<>();
        }

        Set<Long> driverIds = new LinkedHashSet<>();
        Set<Long> vehicleIds = new LinkedHashSet<>();
        Set<Long> warehouseIds = new LinkedHashSet<>();
        for (RoutesEntity route : otherRoutes) {
            driverIds.add(route.getDriverId());
            vehicleIds.add(route.getVehicleId());
            warehouseIds.add(route.getWarehouseId());
        }

        Map<Long, DriversEntity> drivers = new HashMap<>();
        driversDAO.findAllById(driverIds).forEach(item -> drivers.put(item.getId(), item));
        Map<Long, VehiclesEntity> vehicles = new HashMap<>();
        vehiclesDAO.findAllById(vehicleIds).forEach(item -> vehicles.put(item.getId(), item));
        Map<Long, WarehousesEntity> warehouses = new HashMap<>();
        warehousesDAO.findAllById(warehouseIds).forEach(item -> warehouses.put(item.getId(), item));

        List<DispatchResponse.DriverTakenResponse> responses = new ArrayList<>();
        for (RoutesEntity route : otherRoutes) {
            DispatchResponse.DriverTakenResponse item = new DispatchResponse.DriverTakenResponse();
            item.setDriverId(route.getDriverId());
            DriversEntity driver = drivers.get(route.getDriverId());
            item.setDriverName(driver == null ? null : driver.getName());
            VehiclesEntity vehicle = vehicles.get(route.getVehicleId());
            item.setPlateNumber(vehicle == null ? null : vehicle.getPlateNumber());
            WarehousesEntity otherWarehouse = warehouses.get(route.getWarehouseId());
            item.setWarehouseName(otherWarehouse == null ? null : otherWarehouse.getName());
            responses.add(item);
        }
        return responses;
    }

    private DispatchResponse.StopResponse toStop(
            OrdersEntity order,
            StoresEntity store,
            RouteStatus routeStatus
    ) {
        DispatchResponse.StopResponse stop = new DispatchResponse.StopResponse();
        stop.setSequence(order.getSequence());
        stop.setOrderId(order.getId());
        stop.setOrderNumber(order.getOrderNumber());
        stop.setOrderStatus(order.getStatus());
        stop.setDraggable(routeStatus == RouteStatus.DRAFT
                && order.getStatus() == OrderStatus.CONFIRMED);
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

    private DispatchResponse.UnassignedOrderResponse toUnassigned(
            OrdersEntity order,
            StoresEntity store
    ) {
        DispatchResponse.UnassignedOrderResponse response =
                new DispatchResponse.UnassignedOrderResponse();
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
}
