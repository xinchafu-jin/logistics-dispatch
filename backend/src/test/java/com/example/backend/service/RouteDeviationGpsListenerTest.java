package com.example.backend.service;

import com.example.backend.dto.respones.GpsPingSavedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * GPS 存好之後才做偏離判斷、偏離判斷出錯不能讓 GPS 失敗：用真的 Spring 交易跑一次，
 * 確認 @TransactionalEventListener(AFTER_COMMIT) 跟 try/catch 真的有作用。
 *
 * <p>RouteDeviationService 換成 mock：這裡只管「什麼時候呼叫、出錯怎麼辦」，判斷本身由它自己的測試負責。
 * 不用 verifyNoInteractions：每分鐘的升級排程也會呼叫這個 mock，只檢查 onGpsPing。</p>
 */
@SpringBootTest(properties = {
        "app.crypto.password=test-only-password-test-only-password",
        "app.crypto.salt=0123456789abcdef"
})
class RouteDeviationGpsListenerTest {

    private static final LocalDateTime PING_TIME = LocalDateTime.of(2026, 9, 27, 10, 0);
    private static final GpsPingSavedEvent PING = new GpsPingSavedEvent(7L, 22.6273, 120.3014, PING_TIME);

    @MockitoBean
    private RouteDeviationService routeDeviationService;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void GPS的交易commit之後才做偏離判斷() {
        transactionTemplate.executeWithoutResult(status -> {
            eventPublisher.publishEvent(PING);
            verify(routeDeviationService, never()).onGpsPing(anyLong(), anyDouble(), anyDouble(), any());
        });

        verify(routeDeviationService).onGpsPing(7L, 22.6273, 120.3014, PING_TIME);
    }

    @Test
    void GPS的交易rollback就不判斷() {
        transactionTemplate.executeWithoutResult(status -> {
            eventPublisher.publishEvent(PING);
            status.setRollbackOnly();
        });

        verify(routeDeviationService, never()).onGpsPing(anyLong(), anyDouble(), anyDouble(), any());
    }

    @Test
    void 偏離判斷出錯_GPS的交易照樣完成_不會往外丟() {
        doThrow(new IllegalStateException("模擬偏離判斷壞掉"))
                .when(routeDeviationService).onGpsPing(anyLong(), anyDouble(), anyDouble(), any());

        // 例外要是漏出來，會從 commit 那裡丟回 savePing → Controller，司機端收到錯誤、以為 GPS 沒傳成功
        assertDoesNotThrow(() -> transactionTemplate.executeWithoutResult(status -> eventPublisher.publishEvent(PING)));
        verify(routeDeviationService).onGpsPing(7L, 22.6273, 120.3014, PING_TIME);
    }
}
