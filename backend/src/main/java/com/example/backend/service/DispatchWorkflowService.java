package com.example.backend.service;

import com.example.backend.dto.request.OptimizeSlotsDTO;
import com.example.backend.dto.request.ReassignDTO;
import com.example.backend.dto.respones.DispatchDayResponse;
import com.example.backend.dto.respones.DispatchResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private final DispatchSlotService dispatchSlotService;
    private final DispatchDayService dispatchDayService;
    private final PreTripInspectionService preTripInspectionService;
    private final DispatchVehicleMaintenanceGuard maintenanceGuard;

    public DispatchWorkflowService(
            DispatchService dispatchService,
            DispatchBoardService dispatchBoardService,
            DispatchGuardService dispatchGuardService,
            DispatchDraftService dispatchDraftService,
            RoutePlanMetricsService routePlanMetricsService,
            DispatchSlotService dispatchSlotService,
            DispatchDayService dispatchDayService,
            PreTripInspectionService preTripInspectionService,
            DispatchVehicleMaintenanceGuard maintenanceGuard
    ) {
        this.dispatchService = dispatchService;
        this.dispatchBoardService = dispatchBoardService;
        this.dispatchGuardService = dispatchGuardService;
        this.dispatchDraftService = dispatchDraftService;
        this.routePlanMetricsService = routePlanMetricsService;
        this.dispatchSlotService = dispatchSlotService;
        this.dispatchDayService = dispatchDayService;
        this.preTripInspectionService = preTripInspectionService;
        this.maintenanceGuard = maintenanceGuard;
    }

    @Transactional(readOnly = true)
    public List<DispatchDayResponse> getDays(LocalDate from, LocalDate to) {
        return dispatchDayService.getDays(from, to);
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

    /**
     * 依看板上的格子自動排車：格子先轉成車輛與「車輛 → 司機」，OR-Tools 只用這些車，
     * 排完直接帶入司機；沒排到的訂單留在待排單區，沒用到的格子寫進 notices。
     */
    @Transactional
    public DispatchResponse optimizeSlots(OptimizeSlotsDTO dto) {
        dispatchGuardService.assertCanReplan(dto.getDate(), dto.getWarehouseId());

        // 格子全空：由系統挑這一倉需要的車數，配上當天能派的司機；產生的格子一樣交給 plan() 檢查
        List<OptimizeSlotsDTO.Slot> slots = dto.getSlots();
        List<String> notices = new ArrayList<>();
        if (!hasFilledSlot(slots)) {
            DispatchSlotService.AutoSlots auto = dispatchSlotService.autoSlots(dto.getDate(), dto.getWarehouseId());
            slots = auto.getSlots();
            notices.addAll(auto.getNotices());
        }

        DispatchSlotService.SlotPlan plan = dispatchSlotService.plan(dto.getDate(), dto.getWarehouseId(), slots);
        dispatchService.optimize(dto.getDate(), dto.getWarehouseId(), plan.getVehicleIds(), plan.getDriverByVehicle(),
                pinnedVehicleByOrder(slots, plan.getVehicleIds()));
        DispatchResponse board = dispatchBoardService.getBoard(dto.getDate(), dto.getWarehouseId());

        notices.addAll(plan.getNotices());
        Set<Long> routedVehicleIds = new HashSet<>();
        for (DispatchResponse.RouteResponse route : board.getRoutes()) {
            routedVehicleIds.add(route.getVehicleId());
        }
        // 訂單少、車多時 OR-Tools 不一定每台都用，沒用到的車不會有路線，司機也不會派出
        for (Long vehicleId : plan.getVehicleIds()) {
            if (!routedVehicleIds.contains(vehicleId)) {
                String who = plan.getDriverNameByVehicle().get(vehicleId);
                notices.add(plan.getPlateByVehicle().get(vehicleId) + (who == null ? "" : "（" + who + "）")
                        + " 沒有排到訂單");
            }
        }
        board.setNotices(notices);
        return board;
    }

    /**
     * 至少一格選了司機或車；前端沒填的格子也會送來，要逐格看
     */
    private boolean hasFilledSlot(List<OptimizeSlotsDTO.Slot> slots) {
        if (slots == null) {
            return false;
        }
        for (OptimizeSlotsDTO.Slot slot : slots) {
            if (slot.getDriverId() != null || slot.getVehicleId() != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * 格子裡已經有的訂單固定在那格的車上。
     *
     * <p>只固定到這次有出車的車：格子的車不能出（維修中、調倉），plan 已經略過那格並寫進 notices，
     * 那格的單就不固定，交給 OR-Tools 分給其他車，不然整次自動排車都會被一台出不了的車擋住。</p>
     */
    private Map<Long, Long> pinnedVehicleByOrder(List<OptimizeSlotsDTO.Slot> slots, List<Long> dispatchedVehicleIds) {
        Map<Long, Long> pinned = new HashMap<>();
        for (OptimizeSlotsDTO.Slot slot : slots) {
            if (slot.getVehicleId() == null || slot.getOrderIds() == null
                    || !dispatchedVehicleIds.contains(slot.getVehicleId())) {
                continue;
            }
            for (Long orderId : slot.getOrderIds()) {
                pinned.put(orderId, slot.getVehicleId());
            }
        }
        return pinned;
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

    /**
     * 只檢查不發布：AI 一次確認好幾天時，先每天都檢查過，全部通過才開始發布，
     * 不必等到第三天被擋才發現、前兩天也白發布了。
     * 保養預檢要知道每條草稿路線要跑多遠，這一步會叫 OSRM 算距離（不拿道路形狀）。
     */
    public void assertCanPublish(LocalDate date) {
        dispatchGuardService.assertCanPublish(date);
        maintenanceGuard.assertCanPublish(date);
    }

    @Transactional
    public List<DispatchResponse> publish(LocalDate date) {
        dispatchGuardService.assertCanPublish(date);
        routePlanMetricsService.calculateAndStore(date);
        // calculateAndStore 剛把每條路線的總里程存好，直接拿來判斷跑完會不會超過保養里程；超過就整筆退回
        maintenanceGuard.assertCanPublishWithFreshMetrics(date);
        dispatchService.publish(date);
        return dispatchBoardService.getBoards(date);
    }

    @Transactional
    public List<DispatchResponse> withdraw(LocalDate date) {
        dispatchGuardService.assertCanWithdraw(date);
        // 鎖住當天已發布的路線：有人已記出車里程就擋下，其餘的安全檢查作廢，重新發布後要重做
        preTripInspectionService.prepareWithdraw(date);
        dispatchService.withdraw(date);
        // 路線變回草稿後可能被重排，預定形狀要一起刪；重新發布時 calculateAndStore 會重算
        routePlanMetricsService.deletePlannedPaths(date);
        return dispatchBoardService.getBoards(date);
    }
}
