package com.example.backend.dto.respones;

import com.example.backend.constants.OrderStatus;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * 司機端前台查詢 API 的回傳格式。
 *
 * <p>集中放在此 DTO，避免 Controller 或 Service 直接將 Entity 回傳給前端。</p>
 */
public final class DriverPortalResponse {

    private DriverPortalResponse() {
    }

    /** 個資頁資料。 */
    public record ProfileResponse(
            Long id,
            String account,
            String name,
            String phone,
            LocalTime workStart,
            LocalTime workEnd,
            Integer restDuration,
            Integer maxOvertimeMinutes,
            Boolean isActive
    ) {
    }

    /** 任務頁的一筆訂單與門市收貨資訊。 */
    public record TaskResponse(
            Long orderId,
            String orderNumber,
            Long routeId,
            Integer sequence,
            OrderStatus status,
            Integer boxCount,
            String itemDescription,
            String notes,
            String plateNumber,
            String storeCode,
            String storeName,
            String address,
            String contactName,
            String phone,
            LocalTime receivingStart,
            LocalTime receivingEnd
    ) {
    }

    /** 地圖頁的一條路線。 */
    public record RouteMapResponse(
            Long routeId,
            String plateNumber,
            MapPlaceResponse warehouse,
            List<MapStopResponse> stops
    ) {
    }

    /** 地圖上的倉庫座標。 */
    public record MapPlaceResponse(
            String name,
            String address,
            Double lat,
            Double lng
    ) {
    }

    /** 地圖上的門市停靠點。 */
    public record MapStopResponse(
            Long orderId,
            Integer sequence,
            String orderNumber,
            OrderStatus status,
            String storeName,
            String address,
            Double lat,
            Double lng
    ) {
    }

    /** 班表頁的一日摘要。 */
    public record ScheduleDayResponse(
            LocalDate date,
            LocalTime workStart,
            LocalTime workEnd,
            Integer restDuration,
            Integer taskCount,
            List<OrderStatus> taskStatuses
    ) {
    }
}
