package com.example.backend.service;

import com.example.backend.constants.OrderStatus;
import com.example.backend.constants.RouteStatus;
import com.example.backend.dao.OrdersDAO;
import com.example.backend.dao.RoutesDAO;
import com.example.backend.dao.VehiclesDAO;
import com.example.backend.entity.OrdersEntity;
import com.example.backend.entity.RoutesEntity;
import com.example.backend.entity.VehiclesEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** 排車狀態的集中防護，避免重排或撤回破壞已執行的配送歷史。 */
@Service
@Transactional(readOnly = true)
public class DispatchGuardService {

    private final RoutesDAO routesDAO;
    private final OrdersDAO ordersDAO;
    private final VehiclesDAO vehiclesDAO;

    public DispatchGuardService(
            RoutesDAO routesDAO,
            OrdersDAO ordersDAO,
            VehiclesDAO vehiclesDAO
    ) {
        this.routesDAO = routesDAO;
        this.ordersDAO = ordersDAO;
        this.vehiclesDAO = vehiclesDAO;
    }

    public void assertCanReplan(LocalDate date, Long warehouseId) {
        List<RoutesEntity> routes = routesDAO.findByDateAndWarehouseId(date, warehouseId);
        if (routes.stream().anyMatch(route -> route.getStatus() == RouteStatus.PUBLISHED)) {
            throw new IllegalArgumentException("當天已有發布的路線，請先撤回再重排");
        }
        List<Long> routeIds = routes.stream().map(RoutesEntity::getId).toList();
        if (!routeIds.isEmpty()) {
            List<String> delivering = ordersDAO.findByRouteIdIn(routeIds).stream()
                    .filter(order -> order.getStatus() == OrderStatus.IN_DELIVERY)
                    .map(OrdersEntity::getOrderNumber)
                    .toList();
            if (!delivering.isEmpty()) {
                throw new IllegalArgumentException(
                        "已有配送中的訂單，不能一般重排；可直接重新發布或使用司機交接："
                                + String.join("、", delivering));
            }
        }
    }

    public void assertCanPublish(LocalDate date) {
        List<RoutesEntity> routes = routesDAO.findByDate(date);
        if (routes.isEmpty()) {
            throw new IllegalArgumentException("當天沒有可發布的路線：" + date);
        }

        List<String> problems = new ArrayList<>();
        for (RoutesEntity route : routes) {
            if (route.getStatus() != RouteStatus.DRAFT) {
                continue;
            }
            List<OrdersEntity> orders = ordersDAO.findByRouteIdOrderBySequence(route.getId());
            List<OrdersEntity> activeOrders = orders.stream()
                    .filter(order -> order.getStatus() == OrderStatus.CONFIRMED
                            || order.getStatus() == OrderStatus.IN_DELIVERY)
                    .toList();
            if (activeOrders.isEmpty()) {
                continue;
            }
            if (route.getDriverId() == null) {
                problems.add("路線 " + route.getId() + " 尚未指派司機");
            }

            VehiclesEntity vehicle = vehiclesDAO.findById(route.getVehicleId()).orElse(null);
            if (vehicle == null) {
                problems.add("路線 " + route.getId() + " 找不到車輛");
            } else {
                int boxes = activeOrders.stream().mapToInt(OrdersEntity::getBoxCount).sum();
                if (boxes > vehicle.getCapacity()) {
                    problems.add("車輛 " + vehicle.getPlateNumber() + " 裝載 " + boxes
                            + " 箱，超過容量 " + vehicle.getCapacity() + " 箱");
                }
            }
        }

        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("發布前檢查失敗：" + String.join("；", problems));
        }
    }

}
