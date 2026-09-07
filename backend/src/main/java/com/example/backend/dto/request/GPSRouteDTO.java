package com.example.backend.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

/**
 * 司機端導航的路線查詢請求：從目前位置到選定門市。
 *
 * 起點由前端帶手機當下的定位，不從 gps_pings 撈 —— 資料庫那份最新可能已經是
 * 幾十秒前的位置，拿來當導航起點會從錯的地方開始畫線。
 *
 * 四個欄位都用包裝類別 Double 而不是 double：基本型別沒填會變成 0.0，
 * 那是幾內亞灣外海的有效座標，@NotNull 擋不下來，會靜默算出一條錯誤路線。
 */
public class GPSRouteDTO {

    @NotNull(message = "起點緯度不能為空")
    @DecimalMin(value = "-90.0", message = "緯度必須介於 -90 到 90")
    @DecimalMax(value = "90.0", message = "緯度必須介於 -90 到 90")
    private Double fromLat;

    @NotNull(message = "起點經度不能為空")
    @DecimalMin(value = "-180.0", message = "經度必須介於 -180 到 180")
    @DecimalMax(value = "180.0", message = "經度必須介於 -180 到 180")
    private Double fromLng;

    @NotNull(message = "終點緯度不能為空")
    @DecimalMin(value = "-90.0", message = "緯度必須介於 -90 到 90")
    @DecimalMax(value = "90.0", message = "緯度必須介於 -90 到 90")
    private Double toLat;

    @NotNull(message = "終點經度不能為空")
    @DecimalMin(value = "-180.0", message = "經度必須介於 -180 到 180")
    @DecimalMax(value = "180.0", message = "經度必須介於 -180 到 180")
    private Double toLng;

    public Double getFromLat() {
        return fromLat;
    }

    public void setFromLat(Double fromLat) {
        this.fromLat = fromLat;
    }

    public Double getFromLng() {
        return fromLng;
    }

    public void setFromLng(Double fromLng) {
        this.fromLng = fromLng;
    }

    public Double getToLat() {
        return toLat;
    }

    public void setToLat(Double toLat) {
        this.toLat = toLat;
    }

    public Double getToLng() {
        return toLng;
    }

    public void setToLng(Double toLng) {
        this.toLng = toLng;
    }
}
