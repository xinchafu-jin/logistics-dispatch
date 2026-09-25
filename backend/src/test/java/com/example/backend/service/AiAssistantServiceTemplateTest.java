package com.example.backend.service;

import com.example.backend.dto.request.DriversDTO;
import com.example.backend.dto.request.VehiclesDTO;
import com.example.backend.dto.request.WarehousesDTO;
import com.example.backend.dto.respones.DispatchResponse;
import com.example.backend.dto.respones.TemplatesDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AI 的編組工具：查編組看得懂、套用前的檢查、套用後回報的內容。
 *
 * <p>直接呼叫工具方法，不經過模型。編組「編組一」（id 7）：台北倉 王小明 + TN-2001、台北倉 李大華 + 自動配車。</p>
 */
class AiAssistantServiceTemplateTest {

    private static final String CONVERSATION_ID = "admin:1";
    private static final LocalDate TOMORROW = LocalDate.now(ZoneId.of("Asia/Taipei")).plusDays(1);

    private final ToolContext toolContext = new ToolContext(Map.of("conversationId", CONVERSATION_ID));

    private TemplatesService templatesService;
    private AiAssistantService service;

    @BeforeEach
    void setUp() {
        WarehousesService warehousesService = mock(WarehousesService.class);
        DriversService driversService = mock(DriversService.class);
        VehiclesService vehiclesService = mock(VehiclesService.class);
        templatesService = mock(TemplatesService.class);

        WarehousesDTO warehouse = new WarehousesDTO();
        warehouse.setId(1L);
        warehouse.setName("台北倉");
        when(warehousesService.findAll()).thenReturn(List.of(warehouse));
        when(driversService.findAll()).thenReturn(List.of(driver(3L, "王小明"), driver(4L, "李大華")));
        VehiclesDTO vehicle = new VehiclesDTO();
        vehicle.setId(11L);
        vehicle.setPlateNumber("TN-2001");
        when(vehiclesService.findAll()).thenReturn(List.of(vehicle));

        TemplatesDTO template = new TemplatesDTO();
        template.setId(7L);
        template.setName("編組一");
        template.setRoutes(List.of(slot(3L, 11L), slot(4L, null)));
        when(templatesService.findAll()).thenReturn(List.of(template));

        service = new AiAssistantService(null, null, null, "http://unused", null, null,
                null, driversService, warehousesService, templatesService, vehiclesService);
    }

    @Test
    void 查編組_司機車牌都翻成名字() {
        List<String> summaries = service.listTemplates();

        assertEquals(List.of("編組一：台北倉 · 王小明 · TN-2001；台北倉 · 李大華 · （自動配車）"), summaries);
    }

    @Test
    void 套用後回報路線數_排不下_還沒確認_自動配車() {
        DispatchResponse board = board(2, 1, 1, List.of("李大華 自動配車 TN-2002"));
        when(templatesService.applyToDate(7L, TOMORROW)).thenReturn(List.of(board));

        String reply = service.applyTemplate(TOMORROW.toString(), "編組一", toolContext);

        assertTrue(reply.contains("台北倉 排出 2 條路線"), reply);
        assertTrue(reply.contains("1 張單排不下"), reply);
        assertTrue(reply.contains("1 張單還沒確認"), reply);
        assertTrue(reply.contains("李大華 自動配車 TN-2002"), reply);
        assertTrue(reply.contains("尚未發布"), reply);
    }

    @Test
    void 編組名稱打錯_列出有哪些編組() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.applyTemplate(TOMORROW.toString(), "編組1", toolContext));

        assertTrue(e.getMessage().contains("目前有：編組一"), e.getMessage());
        verify(templatesService, never()).applyToDate(any(), any());
    }

    @Test
    void 清單裡有同一天的動作_先擋下_不替調度員刪清單() {
        service.proposePublish(TOMORROW.toString(), toolContext);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.applyTemplate(TOMORROW.toString(), "編組一", toolContext));

        assertTrue(e.getMessage().contains("還有待確認的動作"), e.getMessage());
        assertEquals(1, service.getPlan(CONVERSATION_ID).size());
        verify(templatesService, never()).applyToDate(any(), any());
    }

    @Test
    void 清單裡是別天的動作_不擋() {
        when(templatesService.applyToDate(7L, TOMORROW)).thenReturn(List.of(board(1, 0, 0, List.of())));
        service.proposePublish(TOMORROW.plusDays(1).toString(), toolContext);

        service.applyTemplate(TOMORROW.toString(), "編組一", toolContext);

        verify(templatesService).applyToDate(7L, TOMORROW);
    }

    @Test
    void 過去的日期_擋下() {
        String yesterday = TOMORROW.minusDays(2).toString();

        assertThrows(IllegalArgumentException.class,
                () -> service.applyTemplate(yesterday, "編組一", toolContext));
        verify(templatesService, never()).applyToDate(any(), any());
    }

    private DispatchResponse board(int routes, int unassigned, int pendingConfirm, List<String> notices) {
        DispatchResponse board = new DispatchResponse();
        DispatchResponse.WarehouseResponse warehouse = new DispatchResponse.WarehouseResponse();
        warehouse.setName("台北倉");
        board.setWarehouse(warehouse);
        board.setRoutes(java.util.Collections.nCopies(routes, new DispatchResponse.RouteResponse()));
        board.setUnassignedOrders(java.util.Collections.nCopies(unassigned, new DispatchResponse.UnassignedOrderResponse()));
        board.setPendingConfirmOrders(java.util.Collections.nCopies(pendingConfirm, new DispatchResponse.UnassignedOrderResponse()));
        board.setNotices(notices);
        return board;
    }

    private TemplatesDTO.TemplateRouteResponse slot(Long driverId, Long vehicleId) {
        TemplatesDTO.TemplateRouteResponse slot = new TemplatesDTO.TemplateRouteResponse();
        slot.setWarehouseId(1L);
        slot.setDriverId(driverId);
        slot.setVehicleId(vehicleId);
        return slot;
    }

    private DriversDTO driver(Long id, String name) {
        DriversDTO driver = new DriversDTO();
        driver.setId(id);
        driver.setName(name);
        return driver;
    }
}
