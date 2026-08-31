package com.example.backend.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

import static com.example.backend.constants.ValidMsg.ORDER_BATCH_ROWS_REQUIRED;

public class OrdersBatchDto {
    @NotEmpty(message = ORDER_BATCH_ROWS_REQUIRED)
    @Valid
    private List<OrdersDTO> orders;

    public List<OrdersDTO> getOrders() {
        return orders;
    }

    public void setOrders(List<OrdersDTO> orders) {
        this.orders = orders;
    }
}
