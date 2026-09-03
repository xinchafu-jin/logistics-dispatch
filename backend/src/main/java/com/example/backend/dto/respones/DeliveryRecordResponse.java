package com.example.backend.dto.respones;

import com.example.backend.constants.OrderStatus;

import java.time.LocalDateTime;

/** 抵達、交貨及無人簽收共用的交貨紀錄回應。 */
public record DeliveryRecordResponse(
        Long id,
        Long orderId,
        OrderStatus orderStatus,
        LocalDateTime arrivedAt,
        LocalDateTime deliveredAt,
        Integer deliveredBoxCount,
        String photoUrl,
        String notes,
        Boolean noSignature,
        Long exceptionCaseId
) {
}
