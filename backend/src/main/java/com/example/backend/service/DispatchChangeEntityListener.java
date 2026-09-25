package com.example.backend.service;

import com.example.backend.entity.OrdersEntity;
import com.example.backend.entity.RoutesEntity;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PostRemove;
import jakarta.persistence.PostUpdate;

/**
 * 掛在 OrdersEntity、RoutesEntity 上：Hibernate 寫進資料庫後通知看板推播是哪一天變了。
 *
 * <p>用 Entity 監聽器而不是在各個 Service 裡發事件：寫入訂單、路線的地方有十幾個（點交、配送、排車、發布、
 * 匯入、異常處理…），漏一個那個動作就不會更新畫面，而且很難發現；以後新增的功能也會自動涵蓋。</p>
 *
 * <p>限制：用 @Modifying 的 JPQL 批次更新不會觸發（目前訂單、路線的 DAO 都沒有）；
 * 訂單改配送日期時只看得到新日期，舊的那天不會收到通知。</p>
 *
 * <p>不加 @Component：Spring Boot 會把 Hibernate 的 BeanContainer 設成 SpringBeanContainer，
 * Hibernate 建立監聽器時就會用建構子注入依賴。加了反而會多出一個沒人用的實例。</p>
 */
public class DispatchChangeEntityListener {

    private final DispatchBoardPushService dispatchBoardPushService;

    public DispatchChangeEntityListener(DispatchBoardPushService dispatchBoardPushService) {
        this.dispatchBoardPushService = dispatchBoardPushService;
    }

    @PostPersist
    @PostUpdate
    @PostRemove
    public void changed(Object entity) {
        if (entity instanceof OrdersEntity order) {
            dispatchBoardPushService.markChanged(order.getDeliveryDate());
        } else if (entity instanceof RoutesEntity route) {
            dispatchBoardPushService.markChanged(route.getDate());
        }
    }
}
