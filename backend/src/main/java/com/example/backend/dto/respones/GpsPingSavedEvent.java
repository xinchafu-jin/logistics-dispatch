package com.example.backend.dto.respones;

import java.time.LocalDateTime;

/**
 * 一筆 GPS 存好了。GpsPingsService 發出，RouteDeviationGpsListener 等交易 commit 後拿去做偏離判斷。
 *
 * <p>用事件而不是在 savePing 裡直接呼叫偏離判斷：GPS 上傳不必知道有偏離這件事，
 * 偏離判斷出錯、變慢，也不會拖到 GPS 的交易。</p>
 */
public class GpsPingSavedEvent {

    private final Long driverId;
    private final double lat;
    private final double lng;
    /** 伺服器時間（savePing 用台北時間產生），不是手機時間 */
    private final LocalDateTime timestamp;

    public GpsPingSavedEvent(Long driverId, double lat, double lng, LocalDateTime timestamp) {
        this.driverId = driverId;
        this.lat = lat;
        this.lng = lng;
        this.timestamp = timestamp;
    }

    public Long getDriverId() {
        return driverId;
    }

    public double getLat() {
        return lat;
    }

    public double getLng() {
        return lng;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }
}
