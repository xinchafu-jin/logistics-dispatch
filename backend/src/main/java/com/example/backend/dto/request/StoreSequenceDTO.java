package com.example.backend.dto.request;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 排門市順序：從這個倉出發，把這些門市排成一台車最順的跑法。
 * 只算順序，不看訂單、不寫資料庫。
 */
public class StoreSequenceDTO {

    @NotNull
    private Long warehouseId;

    @NotNull
    private List<@NotNull Long> storeIds;

    public Long getWarehouseId() {
        return warehouseId;
    }

    public void setWarehouseId(Long warehouseId) {
        this.warehouseId = warehouseId;
    }

    public List<Long> getStoreIds() {
        return storeIds;
    }

    public void setStoreIds(List<Long> storeIds) {
        this.storeIds = storeIds;
    }
}
