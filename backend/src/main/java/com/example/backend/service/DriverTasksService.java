package com.example.backend.service;

import com.example.backend.constants.RouteStatus;
import com.example.backend.dao.DriversDAO;
import com.example.backend.dao.OrdersDAO;
import com.example.backend.dao.RoutesDAO;
import com.example.backend.dao.StoresDAO;
import com.example.backend.dao.VehiclesDAO;
import com.example.backend.dao.WarehousesDAO;
import com.example.backend.dto.respones.DriverTasksResponse;
import com.example.backend.entity.DriversEntity;
import com.example.backend.entity.OrdersEntity;
import com.example.backend.entity.RoutesEntity;
import com.example.backend.entity.StoresEntity;
import com.example.backend.entity.VehiclesEntity;
import com.example.backend.entity.WarehousesEntity;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@Transactional(readOnly = true)
public class DriverTasksService {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    private final DriversDAO driversDAO;
    private final RoutesDAO routesDAO;
    private final OrdersDAO ordersDAO;
    private final WarehousesDAO warehousesDAO;
    private final VehiclesDAO vehiclesDAO;
    private final StoresDAO storesDAO;

    public DriverTasksService(
            DriversDAO driversDAO,
            RoutesDAO routesDAO,
            OrdersDAO ordersDAO,
            WarehousesDAO warehousesDAO,
            VehiclesDAO vehiclesDAO,
            StoresDAO storesDAO
    ) {
        this.driversDAO = driversDAO;
        this.routesDAO = routesDAO;
        this.ordersDAO = ordersDAO;
        this.warehousesDAO = warehousesDAO;
        this.vehiclesDAO = vehiclesDAO;
        this.storesDAO = storesDAO;
    }

    public DriverTasksResponse findToday(Long driverId) {
        DriversEntity driver = driversDAO.findById(driverId)
                .orElseThrow(() -> new EntityNotFoundException("找不到司機，ID：" + driverId));
        if (!Boolean.TRUE.equals(driver.getIsActive())) {
            throw new IllegalArgumentException("司機帳號目前未啟用");
        }

        LocalDate today = LocalDate.now(TAIPEI);
        List<RoutesEntity> routes = routesDAO.findByDateAndDriverIdAndStatusOrderByIdAsc(
                today, driverId, RouteStatus.PUBLISHED);
        if (routes.isEmpty()) {
            return new DriverTasksResponse(today, driverId, driver.getName(), List.of());
        }

        Set<Long> routeIds = new LinkedHashSet<>();
        Set<Long> warehouseIds = new LinkedHashSet<>();
        Set<Long> vehicleIds = new LinkedHashSet<>();
        for (RoutesEntity route : routes) {
            routeIds.add(route.getId());
            warehouseIds.add(route.getWarehouseId());
            vehicleIds.add(route.getVehicleId());
        }

        List<OrdersEntity> orders = ordersDAO.findByRouteIdIn(new ArrayList<>(routeIds));
        orders.sort(Comparator
                .comparing(OrdersEntity::getRouteId)
                .thenComparing(OrdersEntity::getSequence,
                        Comparator.nullsLast(Comparator.naturalOrder())));

        Set<Long> storeIds = new LinkedHashSet<>();
        Map<Long, List<OrdersEntity>> ordersByRoute = new HashMap<>();
        for (OrdersEntity order : orders) {
            storeIds.add(order.getStoreId());
            ordersByRoute.computeIfAbsent(order.getRouteId(), ignored -> new ArrayList<>())
                    .add(order);
        }

        Map<Long, WarehousesEntity> warehouses = new HashMap<>();
        for (WarehousesEntity warehouse : warehousesDAO.findAllById(warehouseIds)) {
            warehouses.put(warehouse.getId(), warehouse);
        }
        Map<Long, VehiclesEntity> vehicles = new HashMap<>();
        for (VehiclesEntity vehicle : vehiclesDAO.findAllById(vehicleIds)) {
            vehicles.put(vehicle.getId(), vehicle);
        }
        Map<Long, StoresEntity> stores = new HashMap<>();
        for (StoresEntity store : storesDAO.findAllById(storeIds)) {
            stores.put(store.getId(), store);
        }

        List<DriverTasksResponse.RouteTask> routeTasks = new ArrayList<>();
        for (RoutesEntity route : routes) {
            WarehousesEntity warehouse = warehouses.get(route.getWarehouseId());
            if (warehouse == null) {
                throw new IllegalStateException("配送任務找不到倉庫，ID：" + route.getWarehouseId());
            }
            VehiclesEntity vehicle = vehicles.get(route.getVehicleId());
            if (vehicle == null) {
                throw new IllegalStateException("配送任務找不到車輛，ID：" + route.getVehicleId());
            }

            List<DriverTasksResponse.Stop> stops = new ArrayList<>();
            int totalBoxes = 0;
            for (OrdersEntity order : ordersByRoute.getOrDefault(route.getId(), List.of())) {
                StoresEntity store = stores.get(order.getStoreId());
                if (store == null) {
                    throw new IllegalStateException("配送任務找不到門市，ID：" + order.getStoreId());
                }
                stops.add(toStop(order, store));
                totalBoxes += order.getBoxCount();
            }

            routeTasks.add(new DriverTasksResponse.RouteTask(
                    route.getId(),
                    route.getStatus(),
                    toWarehouse(warehouse),
                    toVehicle(vehicle),
                    route.getTotalDistance(),
                    route.getEstimatedFuelCost(),
                    route.getEstimatedWorkMinutes(),
                    route.getLoadRate(),
                    stops.size(),
                    totalBoxes,
                    stops
            ));
        }

        return new DriverTasksResponse(today, driverId, driver.getName(), routeTasks);
    }

    private DriverTasksResponse.Warehouse toWarehouse(WarehousesEntity warehouse) {
        return new DriverTasksResponse.Warehouse(
                warehouse.getId(),
                warehouse.getWarehouseCode(),
                warehouse.getName(),
                warehouse.getAddress(),
                warehouse.getLat(),
                warehouse.getLng(),
                warehouse.getPhone()
        );
    }

    private DriverTasksResponse.Vehicle toVehicle(VehiclesEntity vehicle) {
        return new DriverTasksResponse.Vehicle(
                vehicle.getId(),
                vehicle.getPlateNumber(),
                vehicle.getVehicleType(),
                vehicle.getCapacity()
        );
    }

    private DriverTasksResponse.Stop toStop(OrdersEntity order, StoresEntity store) {
        return new DriverTasksResponse.Stop(
                order.getSequence(),
                order.getId(),
                order.getOrderNumber(),
                order.getStatus(),
                order.getBoxCount(),
                order.getItemDescription(),
                order.getNotes(),
                store.getId(),
                store.getStoreCode(),
                store.getName(),
                store.getAddress(),
                store.getLat(),
                store.getLng(),
                store.getContactName(),
                store.getPhone(),
                store.getReceivingStart(),
                store.getReceivingEnd()
        );
    }
}
