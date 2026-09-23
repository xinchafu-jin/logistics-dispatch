package com.example.backend.dao;

import com.example.backend.constants.MessageSender;
import com.example.backend.dto.respones.DriverMessageSummaryResponse;
import com.example.backend.entity.DriverMessagesEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface DriverMessagesDAO extends JpaRepository<DriverMessagesEntity, Long> {

    /**
     * 第一次打開對話用：這位司機最新的 50 則。
     * 回傳順序是由新到舊，畫面要由舊到新，Service 拿到後要 reversed()。
     */
    List<DriverMessagesEntity> findTop50ByDriverIdOrderByIdDesc(Long driverId);

    /**
     * 輪詢與重連補抓用：只拿比 afterId 新的訊息，由舊到新，可以直接接在畫面清單後面。
     * 用 id 當游標而不是 createdAt：時間可能重複，id 不會。
     */
    List<DriverMessagesEntity> findByDriverIdAndIdGreaterThanOrderByIdAsc(Long driverId, Long afterId);

    /**
     * 把某位司機對話串裡、某一方發的未讀訊息一次標成已讀，回傳改了幾筆。
     *
     * <p>一句 update 處理整串，不要撈出來逐筆 set。必須在交易裡呼叫（Service 類別上的 @Transactional），
     * Spring Data 不會替自己宣告的查詢開交易，少了會丟 TransactionRequiredException。</p>
     *
     * <p>senderType 是「被讀的那一方」：司機讀的是 ADMIN 發的，管理員讀的是 DRIVER 發的。
     * 呼叫端請用 Service 的 markReadByDriver／markReadByAdmin，不要直接傳 enum，免得傳反。</p>
     */
    @Modifying
    @Query("""
            update DriverMessagesEntity m set m.readAt = :now
            where m.driverId = :driverId and m.senderType = :senderType and m.readAt is null
            """)
    int markRead(@Param("driverId") Long driverId,
                 @Param("senderType") MessageSender senderType,
                 @Param("now") LocalDateTime now);

    /**
     * 後台紅點：每位司機有幾則某一方發的未讀訊息。沒有未讀的司機不會出現在結果裡。
     * select new 會直接呼叫 DriverMessageSummaryResponse 的 (Long, Long) 建構子。
     */
    @Query("""
            select new com.example.backend.dto.respones.DriverMessageSummaryResponse(m.driverId, count(m))
            from DriverMessagesEntity m
            where m.senderType = :senderType and m.readAt is null
            group by m.driverId
            order by m.driverId
            """)
    List<DriverMessageSummaryResponse> countUnreadByDriver(@Param("senderType") MessageSender senderType);
}
