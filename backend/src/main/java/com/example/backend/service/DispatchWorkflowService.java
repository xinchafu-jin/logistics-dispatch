package com.example.backend.service;

import com.example.backend.dto.request.ReassignDTO;
import com.example.backend.dto.respones.DispatchResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * 排車 API 的統一入口。
 *
 * <p>先做狀態防護，再沿用既有排車寫入邏輯，最後由唯讀看板服務重查資料庫。
 * 這樣可維持既有 HTTP 契約，同時防止其他入口略過撤回與歷史保護。</p>
 */
@Service
public class DispatchWorkflowService {

    private final DispatchService dispatchService;
    private final DispatchBoardService dispatchBoardService;
    private final DispatchGuardService dispatchGuardService;
    private final DispatchDraftService dispatchDraftService;
    private final RoutePlanMetricsService routePlanMetricsService;

    public DispatchWorkflowService(
            DispatchService dispatchService,
            DispatchBoardService dispatchBoardService,
            DispatchGuardService dispatchGuardService,
            DispatchDraftService dispatchDraftService,
            RoutePlanMetricsService routePlanMetricsService
    ) {
        this.dispatchService = dispatchService;
        this.dispatchBoardService = dispatchBoardService;
        this.dispatchGuardService = dispatchGuardService;
        this.dispatchDraftService = dispatchDraftService;
        this.routePlanMetricsService = routePlanMetricsService;
    }

    @Transactional(readOnly = true)
    public DispatchResponse getBoard(LocalDate date, Long warehouseId) {
        return dispatchBoardService.getBoard(date, warehouseId);
    }

    @Transactional
    public DispatchResponse optimize(LocalDate date, Long warehouseId, List<Long> vehicleIds) {
        dispatchGuardService.assertCanReplan(date, warehouseId);
        dispatchService.optimize(date, warehouseId, vehicleIds);
        return dispatchBoardService.getBoard(date, warehouseId);
    }

    @Transactional
    public DispatchResponse reassign(ReassignDTO dto) {
        dispatchGuardService.assertCanReplan(dto.getDate(), dto.getWarehouseId());
        if (dto.getRoutes().isEmpty()) {
            dispatchDraftService.clearDraftRoutes(dto.getDate(), dto.getWarehouseId());
        } else {
            dispatchService.reassign(dto);
        }
        return dispatchBoardService.getBoard(dto.getDate(), dto.getWarehouseId());
    }

    @Transactional
    public List<DispatchResponse> publish(LocalDate date) {
        dispatchGuardService.assertCanPublish(date);
        routePlanMetricsService.calculateAndStore(date);
        dispatchService.publish(date);
        return dispatchBoardService.getBoards(date);
    }

    @Transactional
    public List<DispatchResponse> withdraw(LocalDate date) {
        dispatchService.withdraw(date);
        return dispatchBoardService.getBoards(date);
    }
}
