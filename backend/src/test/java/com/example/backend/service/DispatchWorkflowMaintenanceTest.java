package com.example.backend.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class DispatchWorkflowMaintenanceTest {
    @Test void publishChecksMaintenanceBeforeChangingRoutes() {
        DispatchService dispatch = mock(DispatchService.class);
        DispatchGuardService existingGuard = mock(DispatchGuardService.class);
        RoutePlanMetricsService metrics = mock(RoutePlanMetricsService.class);
        DispatchVehicleMaintenanceGuard maintenance = mock(DispatchVehicleMaintenanceGuard.class);
        DispatchWorkflowService workflow = new DispatchWorkflowService(dispatch,
                mock(DispatchBoardService.class), existingGuard, mock(DispatchDraftService.class),
                metrics, mock(DispatchSlotService.class), mock(DispatchDayService.class), maintenance,
                mock(PreTripInspectionService.class));
        LocalDate date = LocalDate.of(2026, 9, 27);
        doThrow(new IllegalArgumentException("禁止出車"))
                .when(maintenance).assertCanPublishWithFreshMetrics(date);

        assertThrows(IllegalArgumentException.class, () -> workflow.publish(date));
        var order = inOrder(existingGuard, metrics, maintenance);
        order.verify(existingGuard).assertCanPublish(date);
        order.verify(metrics).calculateAndStore(date);
        order.verify(maintenance).assertCanPublishWithFreshMetrics(date);
        verify(dispatch, never()).publish(date);
    }

    @Test void preflightChecksMaintenanceWithoutPublishing() {
        DispatchService dispatch = mock(DispatchService.class);
        DispatchGuardService existingGuard = mock(DispatchGuardService.class);
        DispatchVehicleMaintenanceGuard maintenance = mock(DispatchVehicleMaintenanceGuard.class);
        DispatchWorkflowService workflow = new DispatchWorkflowService(dispatch,
                mock(DispatchBoardService.class), existingGuard, mock(DispatchDraftService.class),
                mock(RoutePlanMetricsService.class), mock(DispatchSlotService.class),
                mock(DispatchDayService.class), maintenance, mock(PreTripInspectionService.class));
        LocalDate date = LocalDate.of(2026, 9, 27);

        workflow.assertCanPublish(date);
        verify(existingGuard).assertCanPublish(date);
        verify(maintenance).assertCanPublish(date);
        verifyNoInteractions(dispatch);
    }

    @Test void withdrawInvalidatesInspectionBeforeChangingRoutes() {
        DispatchService dispatch = mock(DispatchService.class);
        DispatchGuardService guard = mock(DispatchGuardService.class);
        PreTripInspectionService inspection = mock(PreTripInspectionService.class);
        DispatchWorkflowService workflow = new DispatchWorkflowService(dispatch,
                mock(DispatchBoardService.class), guard, mock(DispatchDraftService.class),
                mock(RoutePlanMetricsService.class), mock(DispatchSlotService.class),
                mock(DispatchDayService.class), mock(DispatchVehicleMaintenanceGuard.class), inspection);
        LocalDate date = LocalDate.of(2026, 9, 27);

        workflow.withdraw(date);
        var order = inOrder(guard, inspection, dispatch);
        order.verify(guard).assertCanWithdraw(date);
        order.verify(inspection).prepareWithdraw(date);
        order.verify(dispatch).withdraw(date);
    }
}
