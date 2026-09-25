package com.example.backend.service;

import com.example.backend.dto.respones.LeaveNotificationEvent;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Service
public class LeaveNotificationPushService {
    private final SimpMessagingTemplate messaging;

    public LeaveNotificationPushService(SimpMessagingTemplate messaging) {
        this.messaging = messaging;
    }

    /** 資料庫交易成功後才推播，避免前端收到其實已回滾的請假通知。 */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void push(LeaveNotificationEvent event) {
        if (event.notifyAdmins()) {
            messaging.convertAndSend("/topic/admin/leave-requests", event.payload());
        } else {
            messaging.convertAndSendToUser(
                    "DRIVER:" + event.driverId(), "/queue/leave-requests", event.payload());
        }
    }
}
