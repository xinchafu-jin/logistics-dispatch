package com.example.backend.dao;

import com.example.backend.entity.RoutePlannedLegsEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface RoutePlannedLegsDAO extends JpaRepository<RoutePlannedLegsEntity, Long> {

    /** 一條路線的各段，依行駛順序；偏離判斷找「目前這一段」用 */
    List<RoutePlannedLegsEntity> findAllByRouteIdOrderBySequenceAsc(Long routeId);

    /** 後台地圖一次畫好幾條路線：同一條路線的段排在一起，段內依行駛順序 */
    List<RoutePlannedLegsEntity> findAllByRouteIdInOrderByRouteIdAscSequenceAsc(Collection<Long> routeIds);

    /**
     * 重新發布、撤回時整批刪掉這些路線的形狀，回傳刪了幾段。
     *
     * <p>用一句 JPQL 刪，不用 deleteAll(findAll…)：後者會先把每一段連同形狀（每段數 KB）載入再逐筆刪，
     * 只為了刪掉就讀一堆資料。呼叫端要在交易裡（publish／withdraw 本來就是 @Transactional）。</p>
     */
    @Modifying
    @Query("delete from RoutePlannedLegsEntity leg where leg.routeId in :routeIds")
    int deleteAllForRoutes(@Param("routeIds") Collection<Long> routeIds);
}
