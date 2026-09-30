package com.example.backend.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import static com.example.backend.constants.ValidMsg.DELIVERY_BOX_COUNT_MIN;
import static com.example.backend.constants.ValidMsg.DELIVERY_BOX_COUNT_REQUIRED;
import static com.example.backend.constants.ValidMsg.DELIVERY_NOTES_MAX_LENGTH;
import static com.example.backend.constants.ValidMsg.DELIVERY_ORDER_ID_POSITIVE;
import static com.example.backend.constants.ValidMsg.DELIVERY_ORDER_ID_REQUIRED;

/** 司機完成門市交貨時填寫的實際結果。 */
public class DeliverRequestDTO {

    @NotNull(message = DELIVERY_ORDER_ID_REQUIRED)
    @Positive(message = DELIVERY_ORDER_ID_POSITIVE)
    private Long orderId;

    @NotNull(message = DELIVERY_BOX_COUNT_REQUIRED)
    @PositiveOrZero(message = DELIVERY_BOX_COUNT_MIN)
    private Integer boxCount;

    @PositiveOrZero(message = "缺少箱數不能小於 0")
    private Integer shortageBoxCount;

    @PositiveOrZero(message = "損壞箱數不能小於 0")
    @Max(value = 0, message = "貨物損毀由門市回報，司機端不可登記損毀箱數")
    private Integer damagedBoxCount;

    @PositiveOrZero(message = "需要補送箱數不能小於 0")
    private Integer replacementRequiredBoxCount;

    @Size(max = 500, message = "照片網址長度不能超過 500 字")
    private String photoUrl;

    @Size(max = 500, message = DELIVERY_NOTES_MAX_LENGTH)
    private String notes;

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public Integer getBoxCount() {
        return boxCount;
    }

    public void setBoxCount(Integer boxCount) {
        this.boxCount = boxCount;
    }

    public Integer getShortageBoxCount() {
        return shortageBoxCount;
    }

    public void setShortageBoxCount(Integer shortageBoxCount) {
        this.shortageBoxCount = shortageBoxCount;
    }

    public Integer getDamagedBoxCount() {
        return damagedBoxCount;
    }

    public void setDamagedBoxCount(Integer damagedBoxCount) {
        this.damagedBoxCount = damagedBoxCount;
    }

    public Integer getReplacementRequiredBoxCount() {
        return replacementRequiredBoxCount;
    }

    public void setReplacementRequiredBoxCount(Integer replacementRequiredBoxCount) {
        this.replacementRequiredBoxCount = replacementRequiredBoxCount;
    }

    public String getPhotoUrl() {
        return photoUrl;
    }

    public void setPhotoUrl(String photoUrl) {
        this.photoUrl = photoUrl;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }
}
