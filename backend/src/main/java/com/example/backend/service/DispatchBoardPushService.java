package com.example.backend.service;

import com.example.backend.dto.respones.DispatchBoardPushResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 看板推播：訂單或路線有變動時，通知所有管理員「哪一天變了」。
 *
 * <p>變動由 {@link DispatchChangeEntityListener} 回報，不必在十幾個寫入訂單、路線的地方各自呼叫。
 * 同一個交易裡改了幾十筆，只記下不重複的日期，等 commit 成功才一次推出去：
 * 推出去的，資料庫裡一定有；rollback 時什麼都不推（道理同 {@link DriverMessagesPushService}）。</p>
 */
@Service
public class DispatchBoardPushService {

    /** 所有管理員共用的頻道；名稱要跟 WebSocketAuthInterceptor 的訂閱白名單一致 */
    public static final String ADMIN_TOPIC = "/topic/admin/dispatch-board";

    private static final Logger log = LoggerFactory.getLogger(DispatchBoardPushService.class);

    private final SimpMessagingTemplate messagingTemplate;

    public DispatchBoardPushService(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    /** 記下這一天有變動。有交易就等 commit 後推，沒有交易（理論上不會）就直接推 */
    public void markChanged(LocalDate date) {
        if (date == null) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            push(date);
            return;
        }
        pendingPush().dates.add(date);
    }

    /** 這個交易目前記下、還沒推的日期；交易外回傳空集合。給測試確認監聽器有接上 */
    public Set<LocalDate> pendingDates() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return Set.of();
        }
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            if (synchronization instanceof PendingPush pending) {
                return Set.copyOf(pending.dates);
            }
        }
        return Set.of();
    }

    /**
     * 找這個交易已經登記的 PendingPush，沒有就登記一個。
     * 日期集合放在 synchronization 物件裡、不用 bindResource 綁在執行緒上：
     * 交易被暫停（例如巢狀的新交易）時 Spring 會一起暫停 synchronization，兩個交易的日期不會混在一起。
     */
    private PendingPush pendingPush() {
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            if (synchronization instanceof PendingPush pending) {
                return pending;
            }
        }
        PendingPush pending = new PendingPush();
        TransactionSynchronizationManager.registerSynchronization(pending);
        return pending;
    }

    private void push(LocalDate date) {
        try {
            messagingTemplate.convertAndSend(ADMIN_TOPIC, new DispatchBoardPushResponse(date));
        } catch (RuntimeException e) {
            // 推播失敗不能讓 API 回錯誤：資料已經 commit 了。前端重連時會重查，漏掉的只是晚一點更新
            log.warn("看板推播失敗，date={}", date, e);
        }
    }

    private class PendingPush implements TransactionSynchronization {

        private final Set<LocalDate> dates = new LinkedHashSet<>();

        @Override
        public void afterCommit() {
            for (LocalDate date : dates) {
                push(date);
            }
        }
    }
}
