package com.example.backend.service;

import com.example.backend.constants.ExceptionStatus;
import com.example.backend.constants.ExceptionType;
import com.example.backend.constants.LeaveRequestMode;
import com.example.backend.constants.LeaveType;
import com.example.backend.constants.OrderStatus;
import com.example.backend.constants.VehicleStatus;
import com.example.backend.dto.request.DriversDTO;
import com.example.backend.dto.request.GpsPingDTO;
import com.example.backend.dto.request.VehiclesDTO;
import com.example.backend.dto.request.WarehousesDTO;
import com.example.backend.dto.respones.DriverLeaveResponse;
import com.example.backend.dto.respones.ExceptionCaseResponse;
import com.example.backend.dto.respones.ReportResponses;
import com.example.backend.dto.respones.RouteDeviationResponse;
import com.example.backend.dto.respones.VehicleMaintenanceSummaryResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AI 的唯讀查詢工具：待審請假、未結案例外、車輛保養、車隊即時狀況、營運摘要。
 *
 * <p>直接呼叫工具方法，不經過模型；看的是回給模型的那幾行字有沒有帶到調度員在意的資訊。</p>
 */
class AiAssistantServiceQueryToolsTest {

    private DriverLeaveRequestService driverLeaveRequestService;
    private DeliveryExceptionService deliveryExceptionService;
    private GpsPingsService gpsPingsService;
    private RouteDeviationService routeDeviationService;
    private ReportService reportService;
    private VehiclesService vehiclesService;
    private AiAssistantService service;

    @BeforeEach
    void setUp() {
        DriversService driversService = mock(DriversService.class);
        WarehousesService warehousesService = mock(WarehousesService.class);
        vehiclesService = mock(VehiclesService.class);
        driverLeaveRequestService = mock(DriverLeaveRequestService.class);
        deliveryExceptionService = mock(DeliveryExceptionService.class);
        gpsPingsService = mock(GpsPingsService.class);
        routeDeviationService = mock(RouteDeviationService.class);
        reportService = mock(ReportService.class);

        WarehousesDTO warehouse = new WarehousesDTO();
        warehouse.setId(1L);
        warehouse.setName("台北倉");
        when(warehousesService.findAll()).thenReturn(List.of(warehouse));
        DriversDTO driver = new DriversDTO();
        driver.setId(3L);
        driver.setName("王小明");
        when(driversService.findAll()).thenReturn(List.of(driver));

        service = new AiAssistantService(null, null, null, "http://unused", null, null,
                null, driversService, warehousesService, null, vehiclesService,
                driverLeaveRequestService, deliveryExceptionService, gpsPingsService, routeDeviationService,
                reportService);
    }

    @Test
    void 待審請假_時段假列出起訖時間與事由() {
        DriverLeaveResponse leave = mock(DriverLeaveResponse.class);
        when(leave.driverName()).thenReturn("王小明");
        when(leave.workDate()).thenReturn(LocalDate.parse("2026-10-02"));
        when(leave.fullDay()).thenReturn(false);
        when(leave.leaveStart()).thenReturn(LocalTime.of(13, 0));
        when(leave.leaveEnd()).thenReturn(LocalTime.of(17, 0));
        when(leave.leaveType()).thenReturn(LeaveType.SICK);
        when(leave.requestMode()).thenReturn(LeaveRequestMode.TEMPORARY);
        when(leave.requestReason()).thenReturn("看牙醫");
        when(leave.requestedAt()).thenReturn(LocalDateTime.parse("2026-09-30T08:00:00"));
        when(driverLeaveRequestService.findPending()).thenReturn(List.of(leave));

        List<String> lines = service.listPendingLeaveRequests();

        assertEquals(1, lines.size());
        assertEquals("王小明 2026-10-02 13:00～17:00，假別 SICK，申請方式 TEMPORARY，事由：看牙醫（2026-09-30 送出）",
                lines.getFirst());
    }

    @Test
    void 未結案例外_只查OPEN_列出短少與補送單() {
        ExceptionCaseResponse item = mock(ExceptionCaseResponse.class);
        when(item.getId()).thenReturn(12L);
        when(item.getType()).thenReturn(ExceptionType.SHORTAGE);
        when(item.getSourceOrderNumber()).thenReturn("DO-100");
        when(item.getShortageBoxCount()).thenReturn(2);
        when(item.getDamagedBoxCount()).thenReturn(0);
        when(item.getFollowUpOrderNumber()).thenReturn("DO-100-R");
        when(item.getFollowUpDeliveryDate()).thenReturn(LocalDate.parse("2026-10-01"));
        when(item.getFollowUpOrderStatus()).thenReturn(OrderStatus.PENDING_CONFIRM);
        when(item.getCreatedAt()).thenReturn(LocalDateTime.parse("2026-09-30T15:00:00"));
        when(deliveryExceptionService.findAll(ExceptionStatus.OPEN, null)).thenReturn(List.of(item));

        List<String> lines = service.listOpenExceptions();

        String line = lines.getFirst();
        assertTrue(line.contains("案件 12 SHORTAGE，訂單 DO-100，短少 2 箱"), line);
        assertFalse(line.contains("損毀"), line);
        assertTrue(line.contains("補送單 DO-100-R（2026-10-01"), line);
        assertTrue(line.contains("尚未進入確認區"), line);
    }

    @Test
    void 車輛保養_退役的不列_沒資料的寫未知() {
        VehicleMaintenanceSummaryResponse warning = new VehicleMaintenanceSummaryResponse();
        warning.setCurrentOdometerKm(49800);
        warning.setMinorRemainingKm(200);
        warning.setDecision(VehicleMaintenanceSummaryResponse.WARNING);
        warning.setReasons(List.of("小保剩 200 km"));
        when(vehiclesService.findAll()).thenReturn(List.of(
                vehicle("TN-2001", VehicleStatus.AVAILABLE, warning),
                vehicle("TN-2002", VehicleStatus.RETIRED, new VehicleMaintenanceSummaryResponse())));

        List<String> lines = service.listVehicleMaintenance();

        assertEquals(1, lines.size());
        assertEquals("TN-2001（台北倉）狀態 AVAILABLE，里程 49800 km，小保剩 200 km，大保剩 未知，退役剩 未知，"
                + "判定 WARNING：小保剩 200 km", lines.getFirst());
    }

    @Test
    void 車隊即時_列出線上司機與偏離警報_用司機姓名() {
        GpsPingDTO ping = new GpsPingDTO();
        ping.setDriverId(3L);
        ping.setLat(25.04);
        ping.setLng(121.56);
        ping.setTimestamp(LocalDateTime.parse("2026-09-30T10:15:30"));
        when(gpsPingsService.findLatestFleetPositions()).thenReturn(List.of(ping));
        RouteDeviationResponse deviation = new RouteDeviationResponse();
        deviation.setDriverId(3L);
        deviation.setStartedAt(LocalDateTime.parse("2026-09-30T10:05:00"));
        deviation.setStartDistanceMeters(812.4);
        deviation.setEscalatedAt(LocalDateTime.parse("2026-09-30T10:10:00"));
        when(routeDeviationService.findActive()).thenReturn(List.of(deviation));

        List<String> lines = service.getFleetLive();

        assertEquals(List.of(
                "出勤中且有位置回報的司機 1 位",
                "王小明：10:15:30 回報，位置 25.04,121.56",
                "進行中的偏離路線警報 1 筆",
                "王小明：10:05 開始偏離，當時離預定路線 812 公尺，已升級警報"), lines);
    }

    @Test
    void 營運摘要_指定倉庫時帶倉庫ID_不限車輛() {
        ReportResponses.Summary summary = new ReportResponses.Summary();
        summary.setFrom(LocalDate.parse("2026-09-21"));
        summary.setTo(LocalDate.parse("2026-09-27"));
        summary.setTotalOrders(120);
        summary.setCompletedOrders(110);
        summary.setCompletionRatePercent(91.7);
        summary.setCompletionRateDefinition("完成 ÷ 應配送");
        when(reportService.summaryForVehicles(any(), eq(1L), isNull())).thenReturn(summary);

        List<String> lines = service.getReportSummary("2026-09-21", "2026-09-27", "台北倉");

        assertEquals("2026-09-21 ～ 2026-09-27 台北倉", lines.getFirst());
        assertTrue(lines.getLast().contains("完成率 91.7%"), lines.getLast());
    }

    @Test
    void 營運摘要_不填倉庫_查全部倉庫() {
        ReportResponses.Summary summary = new ReportResponses.Summary();
        summary.setFrom(LocalDate.parse("2026-09-21"));
        summary.setTo(LocalDate.parse("2026-09-27"));
        when(reportService.summaryForVehicles(any(), isNull(), isNull())).thenReturn(summary);

        List<String> lines = service.getReportSummary("2026-09-21", "2026-09-27", null);

        verify(reportService).summaryForVehicles(any(), isNull(), isNull());
        assertTrue(lines.getFirst().endsWith("全部倉庫"), lines.getFirst());
        assertTrue(lines.getLast().contains("沒有可計算的訂單"), lines.getLast());
    }

    private VehiclesDTO vehicle(String plate, VehicleStatus status, VehicleMaintenanceSummaryResponse maintenance) {
        VehiclesDTO vehicle = new VehiclesDTO();
        vehicle.setWarehouseId(1L);
        vehicle.setPlateNumber(plate);
        vehicle.setStatus(status);
        vehicle.setMaintenance(maintenance);
        return vehicle;
    }
}
