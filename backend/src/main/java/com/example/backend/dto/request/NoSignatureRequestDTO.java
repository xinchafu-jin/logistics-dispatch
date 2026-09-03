package com.example.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import static com.example.backend.constants.ValidMsg.DELIVERY_NOTES_MAX_LENGTH;
import static com.example.backend.constants.ValidMsg.DELIVERY_ORDER_ID_POSITIVE;
import static com.example.backend.constants.ValidMsg.DELIVERY_ORDER_ID_REQUIRED;
import static com.example.backend.constants.ValidMsg.DELIVERY_PHOTO_MAX_LENGTH;
import static com.example.backend.constants.ValidMsg.DELIVERY_PHOTO_REQUIRED;

/** 門市無人簽收時保存的現場證明。 */
public class NoSignatureRequestDTO {

    @NotNull(message = DELIVERY_ORDER_ID_REQUIRED)
    @Positive(message = DELIVERY_ORDER_ID_POSITIVE)
    private Long orderId;

    /** 第一版先接收已上傳完成的照片網址。 */
    @NotBlank(message = DELIVERY_PHOTO_REQUIRED)
    @Size(max = 500, message = DELIVERY_PHOTO_MAX_LENGTH)
    private String photo;

    @Size(max = 500, message = DELIVERY_NOTES_MAX_LENGTH)
    private String notes;

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public String getPhoto() {
        return photo;
    }

    public void setPhoto(String photo) {
        this.photo = photo;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }
}
