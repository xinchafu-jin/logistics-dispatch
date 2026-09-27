package com.example.backend.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 每分鐘檢查一次進行中的偏離：超過 10 分鐘的升級成警報、開始休息或收車的司機把那筆結束。
 * 實際邏輯在 {@link RouteDeviationService#escalateOverdue}，這裡只負責定時呼叫。
 *
 * <p>為什麼要排程、不能只靠 GPS：升級看的是「時間到了」，司機手機斷線不再回傳時也要升級；
 * 休息中的司機根本傳不了 GPS，「開始休息就結束」也只能靠這裡。</p>
 */
@Service
public class RouteDeviationEscalationScheduler {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    private final RouteDeviationService routeDeviationService;

    public RouteDeviationEscalationScheduler(RouteDeviationService routeDeviationService) {
        this.routeDeviationService = routeDeviationService;
    }

    // fixedDelay：上一次跑完才開始算下一分鐘，兩次不會重疊。
    // 時間明確用台北時間：正式環境的容器預設是 UTC，LocalDateTime.now() 會差 8 小時，跟 startedAt 比會全錯
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void escalateOverdue() {
        routeDeviationService.escalateOverdue(LocalDateTime.now(TAIPEI));
    }
}
