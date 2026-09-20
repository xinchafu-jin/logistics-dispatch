package com.example.backend.service;

import com.example.backend.constants.OrderStatus;
import com.example.backend.constants.RouteStatus;
import com.example.backend.dao.OrdersDAO;
import com.example.backend.dao.RoutesDAO;
import com.example.backend.entity.OrdersEntity;
import com.example.backend.entity.RoutesEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/** 處理拖曳面板全部清空時的草稿刪除，不讓已執行訂單被解綁。 */
@Service
public class DispatchDraftService {

    private final RoutesDAO routesDAO;
    private final OrdersDAO ordersDAO;

    public DispatchDraftService(RoutesDAO routesDAO, OrdersDAO ordersDAO) {
        this.routesDAO = routesDAO;
        this.ordersDAO = ordersDAO;
    }

    @Transactional
    public void clearDraftRoutes(LocalDate date, Long warehouseId) {
        List<RoutesEntity> routes = routesDAO.findByDateAndWarehouseId(date, warehouseId);
        if (routes.stream().anyMatch(route -> route.getStatus() == RouteStatus.PUBLISHED)) {
            throw new IllegalArgumentException("當天已有發布的路線，請先撤回再清空草稿");
        }
        List<Long> routeIds = routes.stream()
                .filter(route -> route.getStatus() == RouteStatus.DRAFT)
                .map(RoutesEntity::getId)
                .toList();
        if (routeIds.isEmpty()) {
            return;
        }

        List<OrdersEntity> orders = ordersDAO.findByRouteIdIn(routeIds);
        List<String> delivering = orders.stream()
                .filter(order -> order.getStatus() == OrderStatus.IN_DELIVERY)
                .map(OrdersEntity::getOrderNumber)
                .toList();
        if (!delivering.isEmpty()) {
            throw new IllegalArgumentException(
                    "已有配送中的訂單，不能清空草稿：" + String.join("、", delivering));
        }
        for (OrdersEntity order : orders.stream()
                .filter(order -> order.getStatus() == OrderStatus.CONFIRMED).toList()) {
            order.setRouteId(null);
            order.setSequence(null);
            order.setAssignedVehicleId(null);
            order.setAssignedDriverId(null);
        }
        ordersDAO.saveAll(orders);
        ordersDAO.flush();
        List<Long> retainedRouteIds = orders.stream()
                .filter(order -> order.getStatus() != OrderStatus.CONFIRMED)
                .map(OrdersEntity::getRouteId)
                .distinct()
                .toList();
        List<Long> deletableRouteIds = routeIds.stream()
                .filter(routeId -> !retainedRouteIds.contains(routeId))
                .toList();
        if (!deletableRouteIds.isEmpty()) {
            routesDAO.deleteVehicleSegmentsByRouteIdIn(deletableRouteIds);
            routesDAO.deleteAllById(deletableRouteIds);
            routesDAO.flush();
        }
    }
}
