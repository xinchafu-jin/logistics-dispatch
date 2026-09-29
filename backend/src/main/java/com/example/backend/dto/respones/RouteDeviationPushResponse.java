package com.example.backend.dto.respones;

import com.example.backend.constants.RouteDeviationPushType;
import com.example.backend.entity.RouteDeviationsEntity;

/**
 * 偏離推播（/topic/admin/route-deviations）的內容，同時也是 Spring 的事件：
 * RouteDeviationService 存好紀錄後用 ApplicationEventPublisher 發出去，
 * RouteDeviationPushService 等交易 commit 成功才真的推（道理同聊天室推播）。
 *
 * <p>用法：{@code eventPublisher.publishEvent(RouteDeviationPushResponse.started(saved))}</p>
 */
public class RouteDeviationPushResponse {

    private RouteDeviationPushType type;
    private RouteDeviationResponse deviation;

    public RouteDeviationPushResponse() {
    }

    public RouteDeviationPushResponse(RouteDeviationPushType type, RouteDeviationResponse deviation) {
        this.type = type;
        this.deviation = deviation;
    }

    /** 新的一筆偏離：後台跳「提示」 */
    public static RouteDeviationPushResponse started(RouteDeviationsEntity entity) {
        return new RouteDeviationPushResponse(RouteDeviationPushType.STARTED, RouteDeviationResponse.from(entity));
    }

    /** 偏離 10 分鐘還沒結束：後台升級成「警報」 */
    public static RouteDeviationPushResponse escalated(RouteDeviationsEntity entity) {
        return new RouteDeviationPushResponse(RouteDeviationPushType.ESCALATED, RouteDeviationResponse.from(entity));
    }

    /** 偏離結束：後台從清單拿掉 */
    public static RouteDeviationPushResponse ended(RouteDeviationsEntity entity) {
        return new RouteDeviationPushResponse(RouteDeviationPushType.ENDED, RouteDeviationResponse.from(entity));
    }

    public RouteDeviationPushType getType() {
        return type;
    }

    public void setType(RouteDeviationPushType type) {
        this.type = type;
    }

    public RouteDeviationResponse getDeviation() {
        return deviation;
    }

    public void setDeviation(RouteDeviationResponse deviation) {
        this.deviation = deviation;
    }
}
