package com.example.backend.service;

import com.example.backend.constants.RouteDeviationEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.example.backend.constants.RouteDeviationEvent.NONE;
import static com.example.backend.constants.RouteDeviationEvent.RESOLVED;
import static com.example.backend.constants.RouteDeviationEvent.STARTED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 偏離狀態機的規格：每筆 GPS 離目前這一段幾公尺 → 這一筆要不要發警報、解除。
 *
 * <p>規則：超過 200 公尺連續 3 筆才發；偏離中不重發；回到 100 公尺內（含）連續 2 筆才解除；
 * 中間斷掉就重新計算。10 秒一筆，3 筆約 30 秒。</p>
 */
class RouteDeviationTrackerTest {

    private final RouteDeviationTracker tracker = new RouteDeviationTracker();

    @Test
    void 單筆飄出去又回來_不發警報() {
        // GPS 在大樓間跳一筆出去很常見，不能每跳一次就吵主管
        assertEquals(List.of(NONE, NONE), feed(240, 60));
        assertFalse(tracker.isOffRoute());
    }

    @Test
    void 連續3筆超過200公尺_第3筆發警報() {
        assertEquals(List.of(NONE, NONE, STARTED), feed(260, 310, 380));
        assertTrue(tracker.isOffRoute());
    }

    @Test
    void 中間夾一筆沒超過200_偏離計數重來() {
        // 250、260 已經兩筆，150 打斷；要從 300 重新數三筆，到 330 才發
        assertEquals(List.of(NONE, NONE, NONE, NONE, NONE, STARTED), feed(250, 260, 150, 300, 320, 330));
    }

    @Test
    void 剛好200公尺不算偏離() {
        // 規則是「超過」200；邊界值寫清楚，實作用 > 還是 >= 才不會各自解讀
        assertEquals(List.of(NONE, NONE, NONE), feed(200, 200, 200));
        assertFalse(tracker.isOffRoute());
    }

    @Test
    void 偏離中不重複發警報() {
        feed(300, 300, 300);

        assertEquals(List.of(NONE, NONE, NONE), feed(400, 500, 450));
        assertTrue(tracker.isOffRoute());
    }

    @Test
    void 偏離中連續2筆回到100公尺內_第2筆解除() {
        feed(300, 300, 300);

        assertEquals(List.of(NONE, RESOLVED), feed(80, 50));
        assertFalse(tracker.isOffRoute());
    }

    @Test
    void 偏離中100到200之間不算回來_也會打斷回來的計數() {
        feed(300, 300, 300);

        // 80 回來一筆；150 在 100～200 之間，不算回來、計數重來；90、100（剛好 100 算回來）才解除
        assertEquals(List.of(NONE, NONE, NONE, RESOLVED), feed(80, 150, 90, 100));
    }

    @Test
    void 偏離中100到200之間也不會重新發警報() {
        feed(300, 300, 300);

        // 還在偏離中，只是沒有 200 那麼遠：不是新的一次偏離，不能再發
        assertEquals(List.of(NONE, NONE, NONE), feed(150, 180, 160));
        assertTrue(tracker.isOffRoute());
    }

    @Test
    void 解除之後再偏離_可以發下一次警報() {
        feed(300, 300, 300);
        feed(50, 50);

        assertEquals(List.of(NONE, NONE, STARTED), feed(300, 300, 300));
    }

    private List<RouteDeviationEvent> feed(double... distances) {
        List<RouteDeviationEvent> events = new ArrayList<>();
        for (double distance : distances) {
            events.add(tracker.onDistance(distance));
        }
        return events;
    }
}
