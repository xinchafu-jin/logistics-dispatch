package com.example.backend.service;

import com.example.backend.constants.RouteStatus;
import com.example.backend.dao.RoutesDAO;
import com.example.backend.dto.respones.RouteDeviationPushResponse;
import com.example.backend.entity.RoutesEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cglib.core.Local;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDate;
import java.util.List;

/**
 * 偏離推播：RouteDeviationService 存好偏離紀錄（新增、升級、結束）後發出 RouteDeviationPushResponse 事件，
 * 這裡等交易 commit 成功才推給所有管理員。
 *
 * <p>為什麼等 commit：後台收到推播會跟 GET /api/fleet/route-deviations 的結果合在一起顯示，
 * 推出去的一定要是資料庫裡查得到的；交易 rollback 時這裡根本不會執行（道理同 {@link DriverMessagesPushService}）。</p>
 */
@Service
public class RouteDeviationPushService {

    /**
     * 所有管理員共用的頻道；名稱要跟 WebSocketAuthInterceptor 的訂閱白名單一致
     */
    public static final String ADMIN_TOPIC = "/topic/admin/route-deviations";

    private static final Logger log = LoggerFactory.getLogger(RouteDeviationPushService.class);

    private final SimpMessagingTemplate messagingTemplate;
    private final RoutesDAO routesDAO;

    public RouteDeviationPushService(SimpMessagingTemplate messagingTemplate, RoutesDAO routesDAO) {
        this.messagingTemplate = messagingTemplate;
        this.routesDAO = routesDAO;
    }


    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void push(RouteDeviationPushResponse push) {
        try {
            messagingTemplate.convertAndSend(ADMIN_TOPIC, push);
        } catch (RuntimeException e) {
            // 推播失敗不能往外丟：這裡是 commit 之後，丟出去會讓已經存好的 GPS 上傳回錯誤。
            // 漏掉的推播，後台重新連線時會重抓進行中的偏離補回來
            log.warn("偏離推播失敗，type={}, deviationId={}",
                    push.getType(), push.getDeviation().getId(), e);
        }
    }
}
