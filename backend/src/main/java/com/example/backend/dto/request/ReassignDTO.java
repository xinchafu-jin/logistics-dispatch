package com.example.backend.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;

import static com.example.backend.constants.ValidMsg.*;

/**
 * 拖曳改派的請求本體。對應 POST /api/dispatch/reassign。
 *
 * <p>與 optimize 的差別只在誰決定內容：optimize 由 OR-Tools 決定哪台車載什麼、
 * 什麼順序，這裡則是調度員在看板上拖出來的結果，後端照做，只負責重算里程與裝載率。</p>
 */
public class ReassignDTO {

    @NotNull(message = REASSIGN_DATE_REQUIRED)
    private LocalDate date;

    @NotNull(message = REASSIGN_WAREHOUSE_ID_REQUIRED)
    private Long warehouseId;

    /**
     * 沒出現在任何一條路線裡的當天訂單會被視為未排入，不需要另外傳。
     *
     * <p>{@code @Valid} 不能省：Bean Validation 預設不會遞迴進集合元素，
     * 少了它 RouteAssignment 裡的標註完全不會執行。</p>
     */

    @NotEmpty(message = REASSIGN_ROUTES_REQUIRED)
    @Valid
    private List<RouteAssignment> routes;


    public LocalDate getDate() {
        return date;
    }

    public void setDate(LocalDate date) {
        this.date = date;
    }

    public Long getWarehouseId() {
        return warehouseId;
    }

    public void setWarehouseId(Long warehouseId) {
        this.warehouseId = warehouseId;
    }

    public List<RouteAssignment> getRoutes() {
        return routes;
    }

    public void setRoutes(List<RouteAssignment> routes) {
        this.routes = routes;
    }

    /**
     * 一台車要載哪些訂單。
     *
     * 不帶既有的 routeId：後端會把當天的草稿整批清掉重建，舊的 routeId 不再存在，
     * 帶了也用不到。日後若要做並行衝突偵測（樂觀鎖）再加回來。</p>
     */
    public static class RouteAssignment {

        @NotNull(message = REASSIGN_VEHICLE_ID_REQUIRED)
        private Long vehicleId;

        private Long driverId;
        /**
         * 陣列順序即配送順序，不另外傳 sequence
         */
        @NotEmpty(message = REASSIGN_ORDER_IDS_REQUIRED)
        private List<Long> orderIds;

        public Long getVehicleId() {
            return vehicleId;
        }

        public void setVehicleId(Long vehicleId) {
            this.vehicleId = vehicleId;
        }

        public List<Long> getOrderIds() {
            return orderIds;
        }

        public void setOrderIds(List<Long> orderIds) {
            this.orderIds = orderIds;
        }

        public Long getDriverId() {
            return driverId;
        }

        public void setDriverId(Long driverId) {
            this.driverId = driverId;
        }
    }
}
