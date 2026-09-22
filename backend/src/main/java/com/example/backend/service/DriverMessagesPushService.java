package com.example.backend.service;

import com.example.backend.dto.respones.DriverMessagePushResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 聊天室推播：DriverMessagesService 存完訊息、標完已讀後會發出 DriverMessagePushResponse 事件，
 * 這裡等交易 commit 成功才真的推出去。
 *
 * <p>為什麼不在 Service 裡直接推：@Transactional 要等方法結束才 commit，
 * 在方法中間推的話，前端收到通知回頭查可能還查不到；交易 rollback 時還會多出一則不存在的訊息。
 * AFTER_COMMIT 保證「推出去的，資料庫裡一定有」；rollback 時這裡根本不會執行。</p>
 */
@Service
public class DriverMessagesPushService {

    /** 所有管理員共用的廣播頻道；名稱要跟 WebSocketAuthInterceptor 的訂閱白名單一致 */
    public static final String ADMIN_TOPIC = "/topic/admin/driver-messages";
    /** 司機的私人頻道；前端訂閱的是 /user/queue/messages，Spring 會依名牌只送給本人 */
    public static final String DRIVER_QUEUE = "/queue/messages";

    private static final Logger log = LoggerFactory.getLogger(DriverMessagesPushService.class);

    private final SimpMessagingTemplate messagingTemplate;

    public DriverMessagesPushService(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void push(DriverMessagePushResponse push) {
        try {
            // 所有管理員都收：共用收件匣，同事的回覆、已讀也要同步
            messagingTemplate.convertAndSend(ADMIN_TOPIC, push);
            // 那位司機也收：名牌格式要跟攔截器貼的一致（角色:userId）
            messagingTemplate.convertAndSendToUser("DRIVER:" + push.getDriverId(), DRIVER_QUEUE, push);
        } catch (RuntimeException e) {
            // 推播失敗不能讓 API 回錯誤：資料已經存好了，回錯誤前端會重送，變成兩則一樣的訊息。
            // 漏掉的推播，前端重連或下次打開對話時會用 afterId 補抓回來。
            log.warn("聊天室推播失敗，driverId={}, type={}", push.getDriverId(), push.getType(), e);
        }
    }
}
