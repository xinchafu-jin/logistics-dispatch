package com.example.backend.service;

import com.example.backend.constants.AiActionType;
import com.example.backend.constants.RouteStatus;
import com.example.backend.dto.request.OptimizeSlotsDTO;
import com.example.backend.dto.request.WarehousesDTO;
import com.example.backend.dto.respones.DispatchDayResponse;
import com.example.backend.dto.respones.DispatchResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.ai.chat.model.ToolContext;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * AI 的撤回、排班概況、自動排車三個工具。
 *
 * <p>直接呼叫工具方法，不經過模型。台北倉（id 1）明天只有一條 TN-2001 的路線，司機王小明。</p>
 */
class AiAssistantServiceWithdrawOptimizeTest {

    private static final String CONVERSATION_ID = "admin:1";
    private static final LocalDate TOMORROW = LocalDate.now(ZoneId.of("Asia/Taipei")).plusDays(1);
    private static final String DATE = TOMORROW.toString();
    private static final String WAREHOUSE = "台北倉";
    private static final Long WAREHOUSE_ID = 1L;
    private static final String PLATE = "TN-2001";

    private final ToolContext toolContext = new ToolContext(Map.of("conversationId", CONVERSATION_ID));

    private DispatchWorkflowService dispatchWorkflowService;
    private AiAssistantService service;

    @BeforeEach
    void setUp() {
        dispatchWorkflowService = mock(DispatchWorkflowService.class);
        WarehousesService warehousesService = mock(WarehousesService.class);

        WarehousesDTO warehouse = new WarehousesDTO();
        warehouse.setId(WAREHOUSE_ID);
        warehouse.setName(WAREHOUSE);
        when(warehousesService.findAll()).thenReturn(List.of(warehouse));

        service = new AiAssistantService(null, null, null, "http://unused", null, null,
                dispatchWorkflowService, null, warehousesService, null, null,
                null, null, null, null, null);
    }

    @Test
    void 撤回_當天沒發布_不加入清單() {
        givenDay(false);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.proposeWithdraw(DATE, toolContext));

        assertTrue(e.getMessage().contains("不需要撤回"), e.getMessage());
        assertTrue(service.getPlan(CONVERSATION_ID).isEmpty());
    }

    @Test
    void 撤回_已有單開始配送_加入清單當下就擋() {
        givenDay(true);
        doThrow(new IllegalArgumentException("已有訂單開始配送，不能撤回（DO-1）；要換人請使用司機交接"))
                .when(dispatchWorkflowService).assertCanWithdraw(TOMORROW);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.proposeWithdraw(DATE, toolContext));

        assertTrue(e.getMessage().contains("不能撤回"), e.getMessage());
        assertTrue(service.getPlan(CONVERSATION_ID).isEmpty());
    }

    @Test
    void 撤回_同一天重複加入_第二次擋下() {
        givenDay(true);

        service.proposeWithdraw(DATE, toolContext);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.proposeWithdraw(DATE, toolContext));

        assertTrue(e.getMessage().contains("已經在待執行清單"), e.getMessage());
        assertEquals(1, service.getPlan(CONVERSATION_ID).size());
        assertEquals(AiActionType.WITHDRAW_DAY, service.getPlan(CONVERSATION_ID).getFirst().getType());
    }

    @Test
    void 清單有撤回_已發布的日子可以加入取消司機() {
        givenDay(true);
        givenBoard(RouteStatus.PUBLISHED);

        service.proposeWithdraw(DATE, toolContext);
        service.proposeUnassignDriver(DATE, WAREHOUSE, PLATE, toolContext);

        assertEquals(2, service.getPlan(CONVERSATION_ID).size());
    }

    @Test
    void 確認_不管加入順序_先撤回再改派最後發布() {
        givenDay(true);
        givenBoard(RouteStatus.PUBLISHED);
        when(dispatchWorkflowService.withdraw(TOMORROW)).thenReturn(new ArrayList<>());
        when(dispatchWorkflowService.publish(TOMORROW)).thenReturn(new ArrayList<>());

        // 發布故意最先加入、撤回放中間
        service.proposePublish(DATE, toolContext);
        service.proposeWithdraw(DATE, toolContext);
        service.proposeUnassignDriver(DATE, WAREHOUSE, PLATE, toolContext);

        service.confirmPlan(CONVERSATION_ID);

        InOrder order = inOrder(dispatchWorkflowService);
        order.verify(dispatchWorkflowService).withdraw(TOMORROW);
        order.verify(dispatchWorkflowService).reassign(any());
        order.verify(dispatchWorkflowService).publish(TOMORROW);
        assertTrue(service.getPlan(CONVERSATION_ID).isEmpty());
    }

    @Test
    void 確認_改派的動作已過期_不會先撤回() {
        givenDay(true);
        givenBoard(RouteStatus.PUBLISHED);
        service.proposeWithdraw(DATE, toolContext);
        service.proposeUnassignDriver(DATE, WAREHOUSE, PLATE, toolContext);
        // 加入清單後路線被別人清掉，TN-2001 已經不在看板上
        DispatchResponse emptyBoard = board(new ArrayList<>());
        when(dispatchWorkflowService.getBoard(TOMORROW, WAREHOUSE_ID)).thenReturn(emptyBoard);

        assertThrows(IllegalArgumentException.class, () -> service.confirmPlan(CONVERSATION_ID));

        verify(dispatchWorkflowService, never()).withdraw(any());
        assertEquals(2, service.getPlan(CONVERSATION_ID).size());
    }

    @Test
    void 排班概況_日期原樣交給看板服務() {
        DispatchDayResponse day = new DispatchDayResponse();
        day.setDate(TOMORROW);
        when(dispatchWorkflowService.getDays(TOMORROW, TOMORROW.plusDays(6))).thenReturn(List.of(day));

        List<DispatchDayResponse> days = service.getDispatchDays(DATE, TOMORROW.plusDays(6).toString());

        assertEquals(1, days.size());
    }

    @Test
    void 自動排車_格子給空的_回報排出幾條路線與提醒() {
        DispatchResponse board = board(List.of(route(RouteStatus.DRAFT)));
        board.setNotices(List.of("TN-2002 沒有排到訂單"));
        when(dispatchWorkflowService.optimizeSlots(any())).thenReturn(board);

        String reply = service.optimizeRoutes(DATE, WAREHOUSE, toolContext);

        ArgumentCaptor<OptimizeSlotsDTO> sent = ArgumentCaptor.forClass(OptimizeSlotsDTO.class);
        verify(dispatchWorkflowService).optimizeSlots(sent.capture());
        assertEquals(TOMORROW, sent.getValue().getDate());
        assertEquals(WAREHOUSE_ID, sent.getValue().getWarehouseId());
        assertTrue(sent.getValue().getSlots().isEmpty());
        assertTrue(reply.contains("台北倉 排出 1 條路線"), reply);
        assertTrue(reply.contains("TN-2002 沒有排到訂單"), reply);
    }

    @Test
    void 自動排車_過去的日期_擋下() {
        String yesterday = TOMORROW.minusDays(2).toString();

        assertThrows(IllegalArgumentException.class,
                () -> service.optimizeRoutes(yesterday, WAREHOUSE, toolContext));

        verify(dispatchWorkflowService, never()).optimizeSlots(any());
    }

    @Test
    void 自動排車_清單裡這個倉那天還有動作_擋下() {
        givenBoard(RouteStatus.DRAFT);
        service.proposeUnassignDriver(DATE, WAREHOUSE, PLATE, toolContext);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.optimizeRoutes(DATE, WAREHOUSE, toolContext));

        assertTrue(e.getMessage().contains("還有待確認的動作"), e.getMessage());
        verify(dispatchWorkflowService, never()).optimizeSlots(any());
    }

    @Test
    void 自動排車_清單裡只有那天的發布_不擋() {
        service.proposePublish(DATE, toolContext);
        when(dispatchWorkflowService.optimizeSlots(any())).thenReturn(board(new ArrayList<>()));

        assertDoesNotThrow(() -> service.optimizeRoutes(DATE, WAREHOUSE, toolContext));
    }

    /** 看板日期列回報明天是否已發布 */
    private void givenDay(boolean published) {
        DispatchDayResponse day = new DispatchDayResponse();
        day.setDate(TOMORROW);
        day.setPublished(published);
        when(dispatchWorkflowService.getDays(TOMORROW, TOMORROW)).thenReturn(List.of(day));
    }

    private void givenBoard(RouteStatus status) {
        DispatchResponse board = board(List.of(route(status)));
        when(dispatchWorkflowService.getBoard(TOMORROW, WAREHOUSE_ID)).thenReturn(board);
    }

    private DispatchResponse.RouteResponse route(RouteStatus status) {
        DispatchResponse.RouteResponse route = new DispatchResponse.RouteResponse();
        route.setVehicleId(11L);
        route.setPlateNumber(PLATE);
        route.setDriverId(3L);
        route.setDriverName("王小明");
        route.setStatus(status);
        route.setStops(new ArrayList<>());
        return route;
    }

    private DispatchResponse board(List<DispatchResponse.RouteResponse> routes) {
        DispatchResponse.WarehouseResponse warehouse = new DispatchResponse.WarehouseResponse();
        warehouse.setId(WAREHOUSE_ID);
        warehouse.setName(WAREHOUSE);

        DispatchResponse board = new DispatchResponse();
        board.setDate(TOMORROW);
        board.setWarehouse(warehouse);
        board.setRoutes(routes);
        board.setUnassignedOrders(new ArrayList<>());
        board.setPendingConfirmOrders(new ArrayList<>());
        board.setNotices(new ArrayList<>());
        return board;
    }
}
