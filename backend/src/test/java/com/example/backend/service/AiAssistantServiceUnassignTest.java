package com.example.backend.service;

import com.example.backend.constants.RouteStatus;
import com.example.backend.dto.request.DriversDTO;
import com.example.backend.dto.request.WarehousesDTO;
import com.example.backend.dto.respones.DispatchResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 取消指派司機加入清單時的兩道檢查：已發布的日期、清單裡同一台車已經有司機異動。
 *
 * <p>只測加入清單這一段，不經過模型：直接呼叫工具方法，看板、倉庫、司機用 mock 回固定資料。</p>
 */
class AiAssistantServiceUnassignTest {

    private static final String CONVERSATION_ID = "admin:1";
    private static final String DATE = "2026-09-12";
    private static final String WAREHOUSE = "仁德轉運站";
    private static final Long WAREHOUSE_ID = 1L;
    private static final String PLATE = "TN-2001";

    private final ToolContext toolContext = new ToolContext(Map.of("conversationId", CONVERSATION_ID));

    private DispatchWorkflowService dispatchWorkflowService;
    private DriversService driversService;
    private AiAssistantService service;

    @BeforeEach
    void setUp() {
        dispatchWorkflowService = mock(DispatchWorkflowService.class);
        driversService = mock(DriversService.class);
        WarehousesService warehousesService = mock(WarehousesService.class);

        WarehousesDTO warehouse = new WarehousesDTO();
        warehouse.setId(WAREHOUSE_ID);
        warehouse.setName(WAREHOUSE);
        when(warehousesService.findAll()).thenReturn(List.of(warehouse));

        // 加入清單用不到模型、主管 Key、班表與訂單，給 null 即可
        service = new AiAssistantService(null, null, null, "http://unused", null, null,
                dispatchWorkflowService, driversService, warehousesService);
    }

    @Test
    void 同一台車重複取消_第二次擋下() {
        givenBoard(RouteStatus.DRAFT, 3L, "王小明");

        service.proposeUnassignDriver(DATE, WAREHOUSE, PLATE, toolContext);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.proposeUnassignDriver(DATE, WAREHOUSE, PLATE, toolContext));

        assertTrue(e.getMessage().contains("已經有司機異動"), e.getMessage());
        assertEquals(1, service.getPlan(CONVERSATION_ID).size());
    }

    @Test
    void 先指派再取消同一台車_取消擋下並指出清單裡那一筆() {
        givenBoard(RouteStatus.DRAFT, 3L, "王小明");
        givenActiveDriver(4L, "D004", "李大華");

        service.proposeAssignDriver(DATE, WAREHOUSE, PLATE, "D004", toolContext);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.proposeUnassignDriver(DATE, WAREHOUSE, PLATE, toolContext));

        assertTrue(e.getMessage().contains("李大華"), e.getMessage());
        assertEquals(1, service.getPlan(CONVERSATION_ID).size());
    }

    @Test
    void 先取消再指派別人_是換人不擋() {
        givenBoard(RouteStatus.DRAFT, 3L, "王小明");
        givenActiveDriver(4L, "D004", "李大華");

        service.proposeUnassignDriver(DATE, WAREHOUSE, PLATE, toolContext);
        service.proposeAssignDriver(DATE, WAREHOUSE, PLATE, "D004", toolContext);

        assertEquals(2, service.getPlan(CONVERSATION_ID).size());
    }

    @Test
    void 車上沒司機但清單有指派_回報清單衝突而不是本來就沒有司機() {
        givenBoard(RouteStatus.DRAFT, null, null);
        givenActiveDriver(4L, "D004", "李大華");

        service.proposeAssignDriver(DATE, WAREHOUSE, PLATE, "D004", toolContext);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.proposeUnassignDriver(DATE, WAREHOUSE, PLATE, toolContext));

        assertTrue(e.getMessage().contains("已經有司機異動"), e.getMessage());
    }

    @Test
    void 已發布的日期_不加入清單() {
        givenBoard(RouteStatus.PUBLISHED, 3L, "王小明");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.proposeUnassignDriver(DATE, WAREHOUSE, PLATE, toolContext));

        assertTrue(e.getMessage().contains("已發布"), e.getMessage());
        assertTrue(service.getPlan(CONVERSATION_ID).isEmpty());
    }

    /** 當天看板只有一條 TN-2001 的路線 */
    private void givenBoard(RouteStatus status, Long driverId, String driverName) {
        DispatchResponse.WarehouseResponse warehouse = new DispatchResponse.WarehouseResponse();
        warehouse.setId(WAREHOUSE_ID);
        warehouse.setName(WAREHOUSE);

        DispatchResponse.RouteResponse route = new DispatchResponse.RouteResponse();
        route.setVehicleId(11L);
        route.setPlateNumber(PLATE);
        route.setDriverId(driverId);
        route.setDriverName(driverName);
        route.setStatus(status);
        route.setStops(new ArrayList<>());

        DispatchResponse board = new DispatchResponse();
        board.setDate(LocalDate.parse(DATE));
        board.setWarehouse(warehouse);
        board.setRoutes(List.of(route));
        when(dispatchWorkflowService.getBoard(LocalDate.parse(DATE), WAREHOUSE_ID)).thenReturn(board);
    }

    private void givenActiveDriver(Long id, String account, String name) {
        DriversDTO driver = new DriversDTO();
        driver.setId(id);
        driver.setAccount(account);
        driver.setName(name);
        driver.setIsActive(true);
        when(driversService.findByAccount(account)).thenReturn(driver);
    }
}
