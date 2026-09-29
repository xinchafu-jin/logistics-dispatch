package com.example.backend.service;

import com.example.backend.constants.OrderStatus;
import com.example.backend.constants.RouteStatus;
import com.example.backend.dao.OrdersDAO;
import com.example.backend.dao.RoutesDAO;
import com.example.backend.dao.VehiclesDAO;
import com.example.backend.entity.OrdersEntity;
import com.example.backend.entity.RoutesEntity;
import com.example.backend.entity.VehiclesEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 發布與出車時「這趟要跑多遠」拿哪裡的數字：預檢叫 OSRM、發布當下與出車用路線存的總里程。
 * 能不能過的規則在 VehicleMaintenanceServiceTest，這裡把它 mock 掉，只看傳進去的公里數。
 */
class DispatchVehicleMaintenanceGuardTest {

    private static final LocalDate DATE = LocalDate.of(2026, 9, 28);

    private RoutesDAO routesDAO;
    private OrdersDAO ordersDAO;
    private RoutePlanMetricsService routePlanMetricsService;
    private VehicleMaintenanceService vehicleMaintenanceService;
    private DispatchVehicleMaintenanceGuard guard;
    private RoutesEntity route;
    private VehiclesEntity vehicle;

    @BeforeEach
    void setUp() {
        routesDAO = mock(RoutesDAO.class);
        ordersDAO = mock(OrdersDAO.class);
        routePlanMetricsService = mock(RoutePlanMetricsService.class);
        vehicleMaintenanceService = mock(VehicleMaintenanceService.class);
        VehiclesDAO vehiclesDAO = mock(VehiclesDAO.class);

        route = new RoutesEntity();
        route.setId(3L);
        route.setDate(DATE);
        route.setVehicleId(2L);
        route.setStatus(RouteStatus.DRAFT);
        // 發布時存的總里程是公尺
        route.setTotalDistance(42500.0);
        when(routesDAO.findByDate(DATE)).thenReturn(List.of(route));
        givenOrder(OrderStatus.CONFIRMED);

        vehicle = new VehiclesEntity();
        vehicle.setId(2L);
        when(vehiclesDAO.findByIdForUpdate(2L)).thenReturn(Optional.of(vehicle));

        guard = new DispatchVehicleMaintenanceGuard(routesDAO, ordersDAO, vehiclesDAO,
                routePlanMetricsService, vehicleMaintenanceService);
    }

    @Test
    void 發布前預檢_草稿站點可能改過_用OSRM重算() {
        when(routePlanMetricsService.plannedKm(route)).thenReturn(38.2);

        guard.assertCanPublish(DATE);

        verify(vehicleMaintenanceService).assertCanDispatch(vehicle, 38.2);
    }

    @Test
    void 發布當下_用剛存好的總里程_不叫OSRM() {
        guard.assertCanPublishWithFreshMetrics(DATE);

        verify(vehicleMaintenanceService).assertCanDispatch(vehicle, 42.5);
        verify(routePlanMetricsService, never()).plannedKm(any());
    }

    @Test
    void 出車_用發布時存的總里程_沒存就只看現況() {
        guard.assertCanStart(route, vehicle);
        verify(vehicleMaintenanceService).assertCanDispatch(vehicle, 42.5);

        route.setTotalDistance(null);
        guard.assertCanStart(route, vehicle);
        verify(vehicleMaintenanceService).assertCanDispatch(eq(vehicle), isNull());
        verify(routePlanMetricsService, never()).plannedKm(any());
    }

    @Test
    void 已發布或只剩結束訂單的路線_不用檢查() {
        route.setStatus(RouteStatus.PUBLISHED);
        guard.assertCanPublishWithFreshMetrics(DATE);

        route.setStatus(RouteStatus.DRAFT);
        givenOrder(OrderStatus.COMPLETED);
        guard.assertCanPublishWithFreshMetrics(DATE);

        verify(vehicleMaintenanceService, never()).assertCanDispatch(any(), any());
    }

    private void givenOrder(OrderStatus status) {
        OrdersEntity order = new OrdersEntity();
        order.setId(9L);
        order.setStatus(status);
        when(ordersDAO.findByRouteIdOrderBySequence(3L)).thenReturn(List.of(order));
    }
}
