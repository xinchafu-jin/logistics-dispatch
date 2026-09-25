package com.example.backend.controller;

import com.example.backend.dto.request.OptimizeSlotsDTO;
import com.example.backend.dto.request.ReassignDTO;
import com.example.backend.dto.request.StoreSequenceDTO;
import com.example.backend.dto.respones.DispatchResponse;
import com.example.backend.dto.respones.RouteMetricsResponse;
import com.example.backend.dto.respones.StoreSequenceResponse;
import com.example.backend.service.DispatchWorkflowService;
import com.example.backend.service.RoutePlanMetricsService;
import com.example.backend.service.StoreSequenceService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/dispatch")
public class DispatchController {

    private final DispatchWorkflowService dispatchWorkflowService;
    private final RoutePlanMetricsService routePlanMetricsService;
    private final StoreSequenceService storeSequenceService;

    public DispatchController(
            DispatchWorkflowService dispatchWorkflowService,
            RoutePlanMetricsService routePlanMetricsService,
            StoreSequenceService storeSequenceService
    ) {
        this.dispatchWorkflowService = dispatchWorkflowService;
        this.routePlanMetricsService = routePlanMetricsService;
        this.storeSequenceService = storeSequenceService;
    }

    /**
     * 排門市順序：編組格子裡的門市，從倉庫出發一台車最順的跑法。只算順序，不看訂單、不寫資料庫。
     */
    @PostMapping("/store-sequence")
    public ResponseEntity<StoreSequenceResponse> storeSequence(@Valid @RequestBody StoreSequenceDTO dto) {
        return ResponseEntity.ok(new StoreSequenceResponse(
                storeSequenceService.sequence(dto.getWarehouseId(), dto.getStoreIds())));
    }

    /**
     * 依看板上的格子自動排車。格子的司機、車輛都是選填，只填司機的格子由後端配車；
     * 回應的 notices 會說明配了哪台車、哪些司機沒帶入、哪些車沒排到訂單。
     */
    @PostMapping("/optimize/slots")
    public ResponseEntity<DispatchResponse> optimizeSlots(@Valid @RequestBody OptimizeSlotsDTO dto) {
        return ResponseEntity.ok(dispatchWorkflowService.optimizeSlots(dto));
    }

    @PostMapping("/optimize")
    public ResponseEntity<DispatchResponse> optimize(
            @RequestParam("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam("warehouseId") Long warehouseId,
            @RequestParam(value = "vehicleIds", required = false) List<Long> vehicleIds
    ) {
        return ResponseEntity.ok(
                dispatchWorkflowService.optimize(
                        date,
                        warehouseId,
                        vehicleIds
                )
        );
    }

    /**
     * 拖曳改派：照調度員在看板上排出來的分派重建路線。
     *
     * <p>不會跑 OR-Tools，也不會調整送來的配送順序，只負責重算里程與裝載率。
     * 參數走 request body 而非 query string，因為內容是巢狀結構。</p>
     */
    @PostMapping("/reassign")
    public ResponseEntity<DispatchResponse> reassign(@Valid @RequestBody ReassignDTO dto) {
        return ResponseEntity.ok(dispatchWorkflowService.reassign(dto));
    }

    /**
     * 調度看板：讀取某天已排定的路線，不會觸發重新排車。
     */
    @GetMapping("/board")
    public ResponseEntity<DispatchResponse> board(
            @RequestParam("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam("warehouseId") Long warehouseId
    ) {
        return ResponseEntity.ok(
                dispatchWorkflowService.getBoard(date, warehouseId)
        );
    }

    /**
     * 發布：一次把當天所有倉庫的草稿排班發出去，司機端才查得到任務。
     *
     * <p>不帶 warehouseId —— 發布是整天一次的動作，不像排車是一次一倉。
     * 回傳每個有路線的倉庫各一包看板資料。</p>
     */
    @PostMapping("/publish")
    public ResponseEntity<List<DispatchResponse>> publish(
            @RequestParam("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        return ResponseEntity.ok(dispatchWorkflowService.publish(date));
    }

    /**
     * 撤回：把當天所有倉庫的發布翻回草稿，之後才能重新排車。
     */
    @PostMapping("/withdraw")
    public ResponseEntity<List<DispatchResponse>> withdraw(
            @RequestParam("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        return ResponseEntity.ok(dispatchWorkflowService.withdraw(date));
    }

    /** 主管查詢單一路線的分段預估、GPS 推估里程與油耗。 */
    @GetMapping("/routes/{routeId}/metrics")
    public ResponseEntity<RouteMetricsResponse> routeMetrics(@PathVariable Long routeId) {
        return ResponseEntity.ok(routePlanMetricsService.getMetrics(routeId));
    }
}
