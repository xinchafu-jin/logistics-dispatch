package com.example.backend.service;

import com.example.backend.dto.respones.GpsPingSavedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 每筆 GPS 存好（交易 commit）之後，交給 RouteDeviationService 做偏離判斷。
 *
 * <p>兩個規則，都是為了「偏離判斷出錯不能讓 GPS 上傳失敗」：</p>
 * <ul>
 *   <li>AFTER_COMMIT：GPS 已經確定存進資料庫才判斷；GPS 的交易 rollback 時這裡不會執行</li>
 *   <li>例外一律在這裡吞掉只記 log：這時 GPS 已經 commit 了，例外再往外丟，
 *       司機端會收到錯誤、以為沒傳成功，但資料其實已經存了</li>
 * </ul>
 * <p>這個方法不能加 @Transactional：要寫資料庫的是 RouteDeviationService.onGpsPing，
 * 它自己用 REQUIRES_NEW 開新交易（原因寫在那個方法上）。</p>
 */
@Service
public class RouteDeviationGpsListener {

    private static final Logger log = LoggerFactory.getLogger(RouteDeviationGpsListener.class);

    private final RouteDeviationService routeDeviationService;

    public RouteDeviationGpsListener(RouteDeviationService routeDeviationService) {
        this.routeDeviationService = routeDeviationService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onGpsSaved(GpsPingSavedEvent event) {
        try {
            routeDeviationService.onGpsPing(
                    event.getDriverId(), event.getLat(), event.getLng(), event.getTimestamp());
        } catch (RuntimeException e) {
            log.warn("偏離判斷失敗，driverId={}", event.getDriverId(), e);
        }
    }
}
