package com.example.backend.dto.respones;

import java.util.List;

/** 排好的門市順序，第一間是從倉庫出發後的第一站 */
public class StoreSequenceResponse {

    private List<Long> storeIds;

    public StoreSequenceResponse() {
    }

    public StoreSequenceResponse(List<Long> storeIds) {
        this.storeIds = storeIds;
    }

    public List<Long> getStoreIds() {
        return storeIds;
    }

    public void setStoreIds(List<Long> storeIds) {
        this.storeIds = storeIds;
    }
}
