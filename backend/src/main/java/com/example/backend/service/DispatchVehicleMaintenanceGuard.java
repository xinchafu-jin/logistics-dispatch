package com.example.backend.service;

import com.example.backend.constants.RouteStatus;
import com.example.backend.dao.OrdersDAO;
import com.example.backend.dao.RoutesDAO;
import com.example.backend.dao.VehiclesDAO;
import com.example.backend.entity.OrdersEntity;
import com.example.backend.entity.RoutesEntity;
import com.example.backend.entity.VehiclesEntity;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 發布與出車前的保養把關：跑完這趟會超過小保、大保或退役里程就擋（規則在 VehicleMaintenanceService）。
 *
 * <p>三個時間點用的「這趟要跑多遠」不一樣：</p>
 * <ul>
 *     <li>發布前預檢：草稿的站點可能剛改過，用 OSRM 重算</li>
 *     <li>發布當下：calculateAndStore 剛在同一個交易算好總里程，直接用</li>
 *     <li>出車：用發布時存的總里程。出車時還沒送任何一站，剩下的就是整條；
 *     不再叫 OSRM，OSRM 暫時掛掉也不會讓司機出不了車</li>
 * </ul>
 */
@Service
@Transactional
public class DispatchVehicleMaintenanceGuard {

    private final RoutesDAO routesDAO;
    private final OrdersDAO ordersDAO;
    private final VehiclesDAO vehiclesDAO;
    private final RoutePlanMetricsService routePlanMetricsService;
    private final VehicleMaintenanceService vehicleMaintenanceService;

    public DispatchVehicleMaintenanceGuard(
            RoutesDAO routesDAO,
            OrdersDAO ordersDAO,
            VehiclesDAO vehiclesDAO,
            RoutePlanMetricsService routePlanMetricsService,
            VehicleMaintenanceService vehicleMaintenanceService
    ) {
        this.routesDAO = routesDAO;
        this.ordersDAO = ordersDAO;
        this.vehiclesDAO = vehiclesDAO;
        this.routePlanMetricsService = routePlanMetricsService;
        this.vehicleMaintenanceService = vehicleMaintenanceService;
    }

    /** 發布前預檢（例如 AI 助理確認計畫前）：草稿站點可能改過，存的總里程不可信，用 OSRM 重算 */
    public void assertCanPublish(LocalDate date) {
        for (RoutesEntity route : draftRoutesWithActiveOrders(date)) {
            assertCanDispatch(route, routePlanMetricsService.plannedKm(route));
        }
    }

    /** 發布當下：calculateAndStore 剛在同一個交易把總里程存進路線，直接用 */
    public void assertCanPublishWithFreshMetrics(LocalDate date) {
        for (RoutesEntity route : draftRoutesWithActiveOrders(date)) {
            assertCanDispatch(route, storedPlannedKm(route));
        }
    }

    /**
     * 出車：司機剛填的行車紀錄器里程已經寫進 lockedVehicle，用新的里程再算一次。
     * 路程用發布時存的總里程；舊資料沒存（null）就當作這趟算不出來，只看現況。
     */
    public void assertCanStart(RoutesEntity route, VehiclesEntity lockedVehicle) {
        vehicleMaintenanceService.assertCanDispatch(lockedVehicle, storedPlannedKm(route));
    }

    /** 鎖住車輛再判斷：主管同時把這台車改成送修時會排隊，不會一邊送修一邊被派出去 */
    private void assertCanDispatch(RoutesEntity route, Double plannedKm) {
        VehiclesEntity vehicle = vehiclesDAO.findByIdForUpdate(route.getVehicleId())
                .orElseThrow(() -> new EntityNotFoundException("找不到路線的車輛，ID：" + route.getVehicleId()));
        vehicleMaintenanceService.assertCanDispatch(vehicle, plannedKm);
    }

    /** 當天還是草稿、而且有待配送訂單的路線：只剩已結束訂單的路線不會再出車，不用檢查 */
    private List<RoutesEntity> draftRoutesWithActiveOrders(LocalDate date) {
        List<RoutesEntity> routes = new ArrayList<>();
        for (RoutesEntity route : routesDAO.findByDate(date)) {
            if (route.getStatus() == RouteStatus.DRAFT && hasActiveOrders(route)) {
                routes.add(route);
            }
        }
        return routes;
    }

    private boolean hasActiveOrders(RoutesEntity route) {
        for (OrdersEntity order : ordersDAO.findByRouteIdOrderBySequence(route.getId())) {
            if (order.getStatus().isActive()) {
                return true;
            }
        }
        return false;
    }

    /** routes.total_distance 存的是公尺 */
    private Double storedPlannedKm(RoutesEntity route) {
        if (route.getTotalDistance() == null) {
            return null;
        }
        return route.getTotalDistance() / 1000.0;
    }
}
