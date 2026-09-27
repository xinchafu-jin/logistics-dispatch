package com.example.backend.service;

import com.example.backend.constants.RouteStatus;
import com.example.backend.dao.OrdersDAO;
import com.example.backend.dao.RoutesDAO;
import com.example.backend.dao.VehiclesDAO;
import com.example.backend.entity.RoutesEntity;
import com.example.backend.entity.VehiclesEntity;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/** 發布及真正出車時共用的車輛保養門檻；預估里程一律包含回倉。 */
@Service
public class DispatchVehicleMaintenanceGuard {
    private final RoutesDAO routes;
    private final OrdersDAO orders;
    private final VehiclesDAO vehicles;
    private final RoutePlanMetricsService metrics;
    private final VehicleMaintenanceService maintenance;

    public DispatchVehicleMaintenanceGuard(RoutesDAO routes, OrdersDAO orders, VehiclesDAO vehicles,
            RoutePlanMetricsService metrics, VehicleMaintenanceService maintenance) {
        this.routes = routes;
        this.orders = orders;
        this.vehicles = vehicles;
        this.metrics = metrics;
        this.maintenance = maintenance;
    }

    /** 排程預檢不能相信以前存的路程，門市順序或訂單可能已變更。 */
    @Transactional
    public void assertCanPublish(LocalDate date) {
        assertCanPublish(date, false);
    }

    /** 發布時 calculateAndStore 剛在同一交易算完路程，可沿用該數值。 */
    @Transactional
    public void assertCanPublishWithFreshMetrics(LocalDate date) {
        assertCanPublish(date, true);
    }

    private void assertCanPublish(LocalDate date, boolean freshMetrics) {
        for (RoutesEntity route : routes.findByDate(date)) {
            if (route.getStatus() != RouteStatus.DRAFT || !hasActiveOrders(route)) continue;
            double plannedKm = freshMetrics && route.getTotalDistance() != null
                    ? route.getTotalDistance() / 1000.0 : metrics.plannedKm(route);
            VehiclesEntity vehicle = vehicles.findByIdForUpdate(route.getVehicleId())
                    .orElseThrow(() -> new EntityNotFoundException("找不到路線車輛，ID：" + route.getVehicleId()));
            maintenance.assertCanDispatch(vehicle, plannedKm);
        }
    }

    @Transactional
    public void assertCanStart(RoutesEntity route, VehiclesEntity lockedVehicle) {
        if (!hasActiveOrders(route)) throw new IllegalArgumentException("路線沒有待配送訂單，不能出車");
        maintenance.assertCanDispatch(lockedVehicle, metrics.plannedKm(route));
    }

    private boolean hasActiveOrders(RoutesEntity route) {
        return orders.findByRouteIdOrderBySequence(route.getId()).stream()
                .anyMatch(order -> order.getStatus().isActive());
    }
}
