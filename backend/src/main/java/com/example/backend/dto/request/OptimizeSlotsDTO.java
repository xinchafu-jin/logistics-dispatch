package com.example.backend.dto.request;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;

/**
 * 依看板上的格子自動排車。
 *
 * <p>一格代表今天的一趟車，司機、車輛都是選填，但至少要填一個才會被拿去排。
 * 只填司機的格子由後端配一台可用車（見 DispatchSlotService）。</p>
 */
public class OptimizeSlotsDTO {

    @NotNull
    private LocalDate date;

    @NotNull
    private Long warehouseId;

    @NotNull
    private List<Slot> slots;

    /**
     * true＝「套用門市訂單」：只排格子裡固定的訂單（Slot.orderIds），其他待排單不動，
     * 用途是讓 OR-Tools 幫每台車算出最順的停靠順序。false＝一般自動排車，待排單也一起分配。
     */
    private boolean pinnedOnly;

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

    public List<Slot> getSlots() {
        return slots;
    }

    public void setSlots(List<Slot> slots) {
        this.slots = slots;
    }

    public boolean isPinnedOnly() {
        return pinnedOnly;
    }

    public void setPinnedOnly(boolean pinnedOnly) {
        this.pinnedOnly = pinnedOnly;
    }

    /** 一個格子：司機與車輛都可以是 null */
    public static class Slot {

        private Long driverId;

        private Long vehicleId;

        /**
         * 調度員已經放進這格的訂單，自動排車時固定在這格的車上，不會被分到別台車。
         * 停靠順序仍交給 OR-Tools 重排。沒有就是 null 或空陣列；有訂單的格子前端保證一定有車。
         */
        private List<Long> orderIds;

        public Long getDriverId() {
            return driverId;
        }

        public void setDriverId(Long driverId) {
            this.driverId = driverId;
        }

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
    }
}
