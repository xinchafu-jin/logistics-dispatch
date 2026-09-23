package com.example.backend.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.ArrayList;
import java.util.List;

import static com.example.backend.constants.ValidMsg.*;

/**
 * 建立或更新常配編組時使用的請求資料。
 * 路線中的 storeIds 順序即為配送順序，後端會從 1 開始產生 sequence。
 */
public class TemplatesRequestDTO {

    @NotBlank(message = TEMPLATE_NAME_REQUIRED)
    @Size(max = 100, message = TEMPLATE_NAME_MAX_LENGTH)
    private String name;

    @Size(max = 500, message = TEMPLATE_NOTES_MAX_LENGTH)
    private String notes;

    @NotEmpty(message = TEMPLATE_ROUTES_REQUIRED)
    @Valid
    private List<TemplateRouteRequest> routes;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public List<TemplateRouteRequest> getRoutes() {
        return routes;
    }

    public void setRoutes(List<TemplateRouteRequest> routes) {
        this.routes = routes;
    }

    public static class TemplateRouteRequest {

        @NotNull(message = TEMPLATE_WAREHOUSE_ID_REQUIRED)
        private Long warehouseId;

        // 車輛、司機都是選填，但至少要有一個（TemplatesService 檢查）
        private Long vehicleId;

        private Long driverId;

        // 這格固定跑的門市，順序即停靠順序；看板存編組時由格子裡訂單的門市組成，沒有就不填
        private List<@NotNull(message = TEMPLATE_STORE_IDS_REQUIRED) Long> storeIds;

        public Long getWarehouseId() {
            return warehouseId;
        }

        public void setWarehouseId(Long warehouseId) {
            this.warehouseId = warehouseId;
        }

        public Long getVehicleId() {
            return vehicleId;
        }

        public void setVehicleId(Long vehicleId) {
            this.vehicleId = vehicleId;
        }

        public Long getDriverId() {
            return driverId;
        }

        public void setDriverId(Long driverId) {
            this.driverId = driverId;
        }

        /** 沒填時回傳空清單，呼叫端不必每次判斷 null */
        public List<Long> getStoreIds() {
            return storeIds == null ? new ArrayList<>() : storeIds;
        }

        public void setStoreIds(List<Long> storeIds) {
            this.storeIds = storeIds;
        }
    }
}
