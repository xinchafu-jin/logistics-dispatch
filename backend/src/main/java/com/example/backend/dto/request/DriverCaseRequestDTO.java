package com.example.backend.dto.request;

import com.example.backend.constants.DriverCaseCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 司機建立例外回報案件（POST /api/driver/cases）。對照 frontend-driver 的 DriverCaseRequest。
 * 是哪位司機、哪條路線、什麼時間都由後端決定，不從前端收。
 */
public class DriverCaseRequestDTO {

    @NotNull(message = "請選擇回報的分類")
    private DriverCaseCategory category;

    /** 跟某張單有關才帶，而且要是今天路線上的單；車輛、路況這類整台車的狀況是 null */
    @Positive(message = "訂單 ID 必須大於 0")
    private Long orderId;

    /** 快選情境加上補充說明組成的一段文字；上限跟 exception_cases.description 的 VARCHAR(1000) 一致 */
    @NotBlank(message = "請點選發生的狀況，或寫一段說明")
    @Size(max = 1000, message = "說明不能超過 1000 字")
    private String description;

    /** 一定要司機自己選，沒有預設值：後台靠它判斷輕重 */
    @NotNull(message = "請選擇還能不能繼續配送")
    private Boolean canContinue;

    /** 先上傳 /api/driver/delivery-photo 拿到的網址；沒拍照是 null */
    @Size(max = 500, message = "照片網址太長")
    private String photoUrl;

    public DriverCaseCategory getCategory() {
        return category;
    }

    public void setCategory(DriverCaseCategory category) {
        this.category = category;
    }

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

    public Boolean getCanContinue() {
        return canContinue;
    }

    public void setCanContinue(Boolean canContinue) {
        this.canContinue = canContinue;
    }

    public String getPhotoUrl() {
        return photoUrl;
    }

    public void setPhotoUrl(String photoUrl) {
        this.photoUrl = photoUrl;
    }
}
