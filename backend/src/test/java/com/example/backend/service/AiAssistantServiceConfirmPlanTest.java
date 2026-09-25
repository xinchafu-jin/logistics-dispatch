package com.example.backend.service;

import com.example.backend.constants.RouteStatus;
import com.example.backend.dto.request.DriversDTO;
import com.example.backend.dto.request.ReassignDTO;
import com.example.backend.dto.request.WarehousesDTO;
import com.example.backend.dto.respones.DispatchResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.ai.chat.model.ToolContext;

import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 確認時多個倉的送出順序。
 *
 * <p>reassign 用 mock 模擬排車的跨倉檢查（實際在 DispatchService.reassign，經 DispatchWorkflowService 呼叫）：同一天一位司機只能在一個倉的路線上，
 * 送出時司機還掛在別倉就丟例外。台北倉 ABC-001 是王小明（D003），桃園倉 XYZ-001 是李大華（D004）。</p>
 */
class AiAssistantServiceConfirmPlanTest {

    private static final String CONVERSATION_ID = "admin:1";
    private static final String MONDAY = "2026-09-14";
    private static final String TUESDAY = "2026-09-15";
    private final ToolContext toolContext = new ToolContext(Map.of("conversationId", CONVERSATION_ID));

    // 模擬資料庫現況：「日期#司機 ID」→ 司機目前所在的倉
    private final Map<String, Long> driverWarehouse = new HashMap<>();

    private DispatchWorkflowService dispatchWorkflowService;
    private AiAssistantService service;

    @BeforeEach
    void setUp() {
        dispatchWorkflowService = mock(DispatchWorkflowService.class);
        DriversService driversService = mock(DriversService.class);
        WarehousesService warehousesService = mock(WarehousesService.class);

        when(warehousesService.findAll()).thenReturn(List.of(warehouse(1L, "台北倉"), warehouse(2L, "桃園倉")));
        when(driversService.findByAccount("D003")).thenReturn(driver(3L, "D003", "王小明"));
        when(driversService.findByAccount("D004")).thenReturn(driver(4L, "D004", "李大華"));

        when(dispatchWorkflowService.reassign(any())).thenAnswer(invocation -> {
            ReassignDTO dto = invocation.getArgument(0);
            for (ReassignDTO.RouteAssignment route : dto.getRoutes()) {
                Long heldBy = driverWarehouse.get(dto.getDate() + "#" + route.getDriverId());
                if (heldBy != null && !heldBy.equals(dto.getWarehouseId())) {
                    throw new IllegalArgumentException(
                            "司機 " + route.getDriverId() + " 當天已排在倉庫 " + heldBy + "，無法重複指派");
                }
            }
            // 送出後，這個倉這一天的司機換成 DTO 裡的
            driverWarehouse.entrySet().removeIf(entry -> entry.getKey().startsWith(dto.getDate() + "#")
                    && entry.getValue().equals(dto.getWarehouseId()));
            for (ReassignDTO.RouteAssignment route : dto.getRoutes()) {
                if (route.getDriverId() != null) {
                    driverWarehouse.put(dto.getDate() + "#" + route.getDriverId(), dto.getWarehouseId());
                }
            }
            return new DispatchResponse();
        });

        service = new AiAssistantService(null, null, null, "http://unused", null, null,
                dispatchWorkflowService, driversService, warehousesService, null, null);
    }

    @Test
    void 跨倉調人_先取消再指派() {
        givenBoard(MONDAY, 1L, "台北倉", 11L, "ABC-001", 3L, "王小明");
        givenBoard(MONDAY, 2L, "桃園倉", 21L, "XYZ-001", 4L, "李大華");

        service.proposeUnassignDriver(MONDAY, "台北倉", "ABC-001", toolContext);
        service.proposeAssignDriver(MONDAY, "桃園倉", "XYZ-001", "D003", toolContext);

        assertDoesNotThrow(() -> service.confirmPlan(CONVERSATION_ID));
    }

    @Test
    void 多天發布_兩天都有問題_一次列出兩天而且一天都沒發() {
        service.proposePublish(MONDAY, toolContext);
        service.proposePublish(TUESDAY, toolContext);
        doThrow(new IllegalArgumentException(MONDAY + " 發布前檢查失敗：ABC-001（王小明）：當天請假"))
                .when(dispatchWorkflowService).assertCanPublish(LocalDate.parse(MONDAY));
        doThrow(new IllegalArgumentException(TUESDAY + " 發布前檢查失敗：XYZ-001 尚未指派司機"))
                .when(dispatchWorkflowService).assertCanPublish(LocalDate.parse(TUESDAY));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.confirmPlan(CONVERSATION_ID));

        assertTrue(e.getMessage().contains(MONDAY + " 發布前檢查失敗"), e.getMessage());
        assertTrue(e.getMessage().contains(TUESDAY + " 發布前檢查失敗"), e.getMessage());
        verify(dispatchWorkflowService, never()).publish(any());
        // 沒確認成功，清單要留著讓調度員修正後再按一次
        assertEquals(2, service.getPlan(CONVERSATION_ID).size());
    }

    @Test
    void 多天發布_全部通過_每天只發一次() {
        service.proposePublish(MONDAY, toolContext);
        service.proposePublish(TUESDAY, toolContext);

        service.confirmPlan(CONVERSATION_ID);

        verify(dispatchWorkflowService, times(1)).publish(LocalDate.parse(MONDAY));
        verify(dispatchWorkflowService, times(1)).publish(LocalDate.parse(TUESDAY));
    }

    @Test
    void 換人先送出_再檢查能不能發布() {
        givenBoard(MONDAY, 1L, "台北倉", 11L, "ABC-001", 3L, "王小明");
        // 王小明請假改派李大華：要先換好人再檢查，否則檢查到的還是請假的王小明
        service.proposeAssignDriver(MONDAY, "台北倉", "ABC-001", "D004", toolContext);
        service.proposePublish(MONDAY, toolContext);

        service.confirmPlan(CONVERSATION_ID);

        InOrder order = inOrder(dispatchWorkflowService);
        order.verify(dispatchWorkflowService).reassign(any());
        order.verify(dispatchWorkflowService).assertCanPublish(LocalDate.parse(MONDAY));
        order.verify(dispatchWorkflowService).publish(LocalDate.parse(MONDAY));
    }

    @Test
    void 跨倉調人_先指派再取消也要成功() {
        givenBoard(MONDAY, 1L, "台北倉", 11L, "ABC-001", 3L, "王小明");
        givenBoard(MONDAY, 2L, "桃園倉", 21L, "XYZ-001", 4L, "李大華");

        // 桃園那組先加入，而且它也會放出李大華；照加入順序送的話，王小明還掛在台北會被擋
        service.proposeAssignDriver(MONDAY, "桃園倉", "XYZ-001", "D003", toolContext);
        service.proposeUnassignDriver(MONDAY, "台北倉", "ABC-001", toolContext);

        assertDoesNotThrow(() -> service.confirmPlan(CONVERSATION_ID));
    }

    @Test
    void 跨倉互換_整批擋下而且一組都沒送() {
        givenBoard(MONDAY, 1L, "台北倉", 11L, "ABC-001", 3L, "王小明");
        givenBoard(MONDAY, 2L, "桃園倉", 21L, "XYZ-001", 4L, "李大華");

        service.proposeAssignDriver(MONDAY, "台北倉", "ABC-001", "D004", toolContext);
        service.proposeAssignDriver(MONDAY, "桃園倉", "XYZ-001", "D003", toolContext);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.confirmPlan(CONVERSATION_ID));
        assertTrue(e.getMessage().contains("互換"), e.getMessage());
        verify(dispatchWorkflowService, never()).reassign(any());
        // 沒執行成功，清單要留著讓調度員調整
        assertEquals(2, service.getPlan(CONVERSATION_ID).size());
    }

    @Test
    void 不同天不互相卡住() {
        // 週一王小明在台北、週二李大華在桃園。週一台北改李大華、週二桃園改王小明：
        // 不分日期比對的話，兩組會被誤判成互相在等
        givenBoard(MONDAY, 1L, "台北倉", 11L, "ABC-001", 3L, "王小明");
        givenBoard(TUESDAY, 2L, "桃園倉", 21L, "XYZ-001", 4L, "李大華");

        service.proposeAssignDriver(MONDAY, "台北倉", "ABC-001", "D004", toolContext);
        service.proposeAssignDriver(TUESDAY, "桃園倉", "XYZ-001", "D003", toolContext);

        assertDoesNotThrow(() -> service.confirmPlan(CONVERSATION_ID));
        verify(dispatchWorkflowService, times(2)).reassign(any());
    }

    /** 某天某倉的看板只有一條路線，同時記進模擬的資料庫現況 */
    private void givenBoard(String date, Long warehouseId, String warehouseName, Long vehicleId, String plate,
                            Long driverId, String driverName) {
        DispatchResponse.WarehouseResponse warehouse = new DispatchResponse.WarehouseResponse();
        warehouse.setId(warehouseId);
        warehouse.setName(warehouseName);

        DispatchResponse.RouteResponse route = new DispatchResponse.RouteResponse();
        route.setVehicleId(vehicleId);
        route.setPlateNumber(plate);
        route.setDriverId(driverId);
        route.setDriverName(driverName);
        route.setStatus(RouteStatus.DRAFT);
        route.setStops(new ArrayList<>());

        DispatchResponse board = new DispatchResponse();
        board.setDate(LocalDate.parse(date));
        board.setWarehouse(warehouse);
        board.setRoutes(List.of(route));
        when(dispatchWorkflowService.getBoard(LocalDate.parse(date), warehouseId)).thenReturn(board);

        driverWarehouse.put(LocalDate.parse(date) + "#" + driverId, warehouseId);
    }

    private WarehousesDTO warehouse(Long id, String name) {
        WarehousesDTO warehouse = new WarehousesDTO();
        warehouse.setId(id);
        warehouse.setName(name);
        return warehouse;
    }

    private DriversDTO driver(Long id, String account, String name) {
        DriversDTO driver = new DriversDTO();
        driver.setId(id);
        driver.setAccount(account);
        driver.setName(name);
        driver.setIsActive(true);
        return driver;
    }
}
