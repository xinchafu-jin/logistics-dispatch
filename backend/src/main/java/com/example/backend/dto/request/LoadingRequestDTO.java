package com.example.backend.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import static com.example.backend.constants.ValidMsg.DELIVERY_ORDER_ID_POSITIVE;
import static com.example.backend.constants.ValidMsg.DELIVERY_ORDER_ID_REQUIRED;
import static com.example.backend.constants.ValidMsg.LOADING_BOX_COUNT_MIN;
import static com.example.backend.constants.ValidMsg.LOADING_BOX_COUNT_REQUIRED;
import static com.example.backend.constants.ValidMsg.LOADING_NOTES_MAX_LENGTH;

/** 司機在倉庫點交裝車時填寫的實點結果。 */
public class LoadingRequestDTO {

    @NotNull(message = DELIVERY_ORDER_ID_REQUIRED)
    @Positive(message = DELIVERY_ORDER_ID_POSITIVE)
    private Long orderId;

    /** 實際點到、外箱完好的箱數。跟訂單箱數比對、決定點交成不成功的是後端。 */
    @NotNull(message = LOADING_BOX_COUNT_REQUIRED)
    @PositiveOrZero(message = LOADING_BOX_COUNT_MIN)
    private Integer loadedBoxCount;

    @Size(max = 500, message = LOADING_NOTES_MAX_LENGTH)
    private String notes;

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public Integer getLoadedBoxCount() {
        return loadedBoxCount;
    }

    public void setLoadedBoxCount(Integer loadedBoxCount) {
        this.loadedBoxCount = loadedBoxCount;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }
}
