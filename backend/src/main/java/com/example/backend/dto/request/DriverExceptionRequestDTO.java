package com.example.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** 司機針對目前配送訂單回報一般異常。 */
public class DriverExceptionRequestDTO {

    @NotNull(message = "訂單 ID 不能為空")
    @Positive(message = "訂單 ID 必須大於 0")
    private Long orderId;

    @NotBlank(message = "異常說明不能為空")
    @Size(max = 1000, message = "異常說明不能超過 1000 字")
    private String description;

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}
