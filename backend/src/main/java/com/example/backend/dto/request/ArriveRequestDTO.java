package com.example.backend.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import static com.example.backend.constants.ValidMsg.DELIVERY_ORDER_ID_POSITIVE;
import static com.example.backend.constants.ValidMsg.DELIVERY_ORDER_ID_REQUIRED;

/** 司機抵達門市時送出的訂單識別資料。 */
public class ArriveRequestDTO {

    @NotNull(message = DELIVERY_ORDER_ID_REQUIRED)
    @Positive(message = DELIVERY_ORDER_ID_POSITIVE)
    private Long orderId;

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }
}
