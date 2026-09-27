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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DispatchVehicleMaintenanceGuardTest {
    private final LocalDate date = LocalDate.of(2026, 9, 27);
    private RoutesDAO routes;
    private OrdersDAO orders;
    private VehiclesDAO vehicles;
    private RoutePlanMetricsService metrics;
    private VehicleMaintenanceService maintenance;
    private DispatchVehicleMaintenanceGuard guard;
    private RoutesEntity route;
    private VehiclesEntity vehicle;

    @BeforeEach void setup() {
        routes = mock(RoutesDAO.class);
        orders = mock(OrdersDAO.class);
        vehicles = mock(VehiclesDAO.class);
        metrics = mock(RoutePlanMetricsService.class);
        maintenance = mock(VehicleMaintenanceService.class);
        guard = new DispatchVehicleMaintenanceGuard(routes, orders, vehicles, metrics, maintenance);
        route = new RoutesEntity(); route.setId(1L); route.setVehicleId(2L);
        route.setDate(date); route.setStatus(RouteStatus.DRAFT); route.setTotalDistance(30000.0);
        vehicle = new VehiclesEntity(); vehicle.setId(2L); vehicle.setPlateNumber("TEST-2");
        OrdersEntity order = new OrdersEntity(); order.setStatus(OrderStatus.CONFIRMED);
        when(routes.findByDate(date)).thenReturn(List.of(route));
        when(orders.findByRouteIdOrderBySequence(1L)).thenReturn(List.of(order));
        when(vehicles.findByIdForUpdate(2L)).thenReturn(Optional.of(vehicle));
    }

    @Test void publishUsesFreshRoundTripDistanceAndChecksVehicle() {
        guard.assertCanPublishWithFreshMetrics(date);
        verify(maintenance).assertCanDispatch(vehicle, 30.0);
        verifyNoInteractions(metrics);
    }

    @Test void preflightRecomputesPlanInsteadOfTrustingStaleDistance() {
        when(metrics.plannedKm(route)).thenReturn(32.5);
        guard.assertCanPublish(date);
        verify(maintenance).assertCanDispatch(vehicle, 32.5);
    }

    @Test void alreadyPublishedOrCompletedOnlyRoutesAreNotRechecked() {
        route.setStatus(RouteStatus.PUBLISHED);
        guard.assertCanPublishWithFreshMetrics(date);
        verifyNoInteractions(maintenance, metrics, vehicles);

        route.setStatus(RouteStatus.DRAFT);
        OrdersEntity completed = new OrdersEntity(); completed.setStatus(OrderStatus.COMPLETED);
        when(orders.findByRouteIdOrderBySequence(1L)).thenReturn(List.of(completed));
        guard.assertCanPublishWithFreshMetrics(date);
        verifyNoInteractions(maintenance, metrics, vehicles);
    }

    @Test void driverStartRechecksCurrentPlanAndRejectsMaintenanceBlock() {
        when(metrics.plannedKm(route)).thenReturn(32.5);
        doThrow(new IllegalArgumentException("TEST-2 禁止出車"))
                .when(maintenance).assertCanDispatch(vehicle, 32.5);
        assertThrows(IllegalArgumentException.class, () -> guard.assertCanStart(route, vehicle));
    }
}
