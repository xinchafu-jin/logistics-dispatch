package com.example.backend.dto.respones;

/**
 * 後台紅點用：某位司機有幾則訊息還沒被管理員讀。
 * 由 DAO 的 JPQL「select new ...(m.driverId, count(m))」直接建出來，
 * 所以一定要有 (Long, Long) 的建構子；count 回傳的是 Long，寫成 int 會找不到建構子。
 */
public class DriverMessageSummaryResponse {

    private Long driverId;
    private Long unreadCount;

    /** Jackson 反序列化用 */
    public DriverMessageSummaryResponse() {
    }

    public DriverMessageSummaryResponse(Long driverId, Long unreadCount) {
        this.driverId = driverId;
        this.unreadCount = unreadCount;
    }

    public Long getDriverId() {
        return driverId;
    }

    public void setDriverId(Long driverId) {
        this.driverId = driverId;
    }

    public Long getUnreadCount() {
        return unreadCount;
    }

    public void setUnreadCount(Long unreadCount) {
        this.unreadCount = unreadCount;
    }
}
