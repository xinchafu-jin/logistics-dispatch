package com.example.backend.service;

import com.example.backend.dto.respones.DispatchBoardPushResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 看板推播的收集與送出：同一個交易只推不重複的日期、commit 後才推、rollback 不推。
 *
 * <p>不開真的交易：用 TransactionSynchronizationManager 手動模擬「交易進行中」，
 * 再自己呼叫 afterCommit／afterCompletion，等於 Spring 在 commit 或 rollback 時會做的事。</p>
 */
class DispatchBoardPushServiceTest {

    private static final LocalDate MON = LocalDate.of(2026, 9, 28);
    private static final LocalDate TUE = LocalDate.of(2026, 9, 29);

    private SimpMessagingTemplate messagingTemplate;
    private DispatchBoardPushService service;

    @BeforeEach
    void setUp() {
        messagingTemplate = mock(SimpMessagingTemplate.class);
        service = new DispatchBoardPushService(messagingTemplate);
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void 同一個交易改很多筆_commit後每天只推一次() {
        TransactionSynchronizationManager.initSynchronization();
        service.markChanged(MON);
        service.markChanged(MON);
        service.markChanged(TUE);
        service.markChanged(MON);

        verify(messagingTemplate, never()).convertAndSend(any(String.class), any(Object.class));
        commit();

        assertEquals(List.of(MON, TUE), pushedDates(2));
    }

    @Test
    void rollback就不推() {
        TransactionSynchronizationManager.initSynchronization();
        service.markChanged(MON);

        rollback();

        verify(messagingTemplate, never()).convertAndSend(any(String.class), any(Object.class));
    }

    @Test
    void 不在交易裡_直接推() {
        service.markChanged(MON);

        assertEquals(List.of(MON), pushedDates(1));
    }

    @Test
    void 沒有日期_不推() {
        service.markChanged(null);

        verify(messagingTemplate, never()).convertAndSend(any(String.class), any(Object.class));
    }

    @Test
    void 推播失敗_不影響已經commit的交易() {
        doThrow(new IllegalStateException("broker 掛了"))
                .when(messagingTemplate).convertAndSend(eq(DispatchBoardPushService.ADMIN_TOPIC), any(Object.class));
        TransactionSynchronizationManager.initSynchronization();
        service.markChanged(MON);

        assertDoesNotThrow(this::commit);
    }

    private void commit() {
        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        for (TransactionSynchronization synchronization : synchronizations) {
            synchronization.afterCommit();
        }
        for (TransactionSynchronization synchronization : synchronizations) {
            synchronization.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
        }
    }

    private void rollback() {
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        }
    }

    private List<LocalDate> pushedDates(int times) {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate, times(times)).convertAndSend(eq(DispatchBoardPushService.ADMIN_TOPIC), captor.capture());
        return captor.getAllValues().stream()
                .map(payload -> ((DispatchBoardPushResponse) payload).getDate())
                .toList();
    }
}
