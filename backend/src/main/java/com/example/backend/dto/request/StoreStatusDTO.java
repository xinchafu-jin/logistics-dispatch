package com.example.backend.dto.request;

import com.example.backend.constants.StoreStatus;
import jakarta.validation.constraints.NotNull;

public class StoreStatusDTO {

    @NotNull(message = "門市狀態不能為空")
    private StoreStatus status;

    public StoreStatus getStatus() {
        return status;
    }

    public void setStatus(StoreStatus status) {
        this.status = status;
    }
}