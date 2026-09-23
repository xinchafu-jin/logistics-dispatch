package com.example.backend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 啟動時及每日更新中油超級柴油牌價；失敗時保留既有有效牌價。 */
@Component
public class CpcFuelPriceSyncScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(CpcFuelPriceSyncScheduler.class);

    private final CpcFuelPriceSyncService cpcFuelPriceSyncService;

    public CpcFuelPriceSyncScheduler(CpcFuelPriceSyncService cpcFuelPriceSyncService) {
        this.cpcFuelPriceSyncService = cpcFuelPriceSyncService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void syncAfterStartup() {
        syncSafely("系統啟動");
    }

    @Scheduled(cron = "${app.cpc.sync-cron:0 15 0 * * *}", zone = "Asia/Taipei")
    public void syncDaily() {
        syncSafely("每日排程");
    }

    private void syncSafely(String trigger) {
        try {
            cpcFuelPriceSyncService.syncSuperDieselPrice();
            LOGGER.info("{}同步中油超級柴油牌價成功", trigger);
        } catch (RuntimeException exception) {
            LOGGER.warn("{}同步中油超級柴油牌價失敗，繼續使用既有有效牌價：{}",
                    trigger, exception.getMessage());
        }
    }
}
