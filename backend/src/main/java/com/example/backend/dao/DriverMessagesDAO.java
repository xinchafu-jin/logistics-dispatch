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
import java.util.Collection;
import java.util.List;

/**
 * 聊天訊息。一位司機有一串一般對話（exceptionCaseId 是 null），加上每件例外回報案件各一串。
 *
 * <p>一般對話的查詢名稱或條件都帶 ExceptionCaseIdIsNull／exceptionCaseId is null：少了它，
 * 案件訊息會混進一般對話，紅點也會把案件未讀算進去。查詢都先比 driverId，走 V14 的
 * (driver_id, exception_case_id, id) 索引。</p>
 */
@Repository
public interface DriverMessagesDAO extends JpaRepository<DriverMessagesEntity, Long> {

    // ── 一般對話 ──

    /**
     * 第一次打開對話用：這位司機一般對話最新的 50 則。
     * 回傳順序是由新到舊，畫面要由舊到新，Service 拿到後要 reversed()。
     */
    List<DriverMessagesEntity> findTop50ByDriverIdAndExceptionCaseIdIsNullOrderByIdDesc(Long driverId);

    /**
     * 輪詢與重連補抓用：只拿比 afterId 新的訊息，由舊到新，可以直接接在畫面清單後面。
     * 用 id 當游標而不是 createdAt：時間可能重複，id 不會。
     */
    List<DriverMessagesEntity> findByDriverIdAndExceptionCaseIdIsNullAndIdGreaterThanOrderByIdAsc(
            Long driverId, Long afterId);

    /**
     * 把某位司機一般對話裡、某一方發的未讀訊息一次標成已讀，回傳改了幾筆。
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
            where m.driverId = :driverId and m.exceptionCaseId is null
              and m.senderType = :senderType and m.readAt is null
            """)
    int markRead(@Param("driverId") Long driverId,
                 @Param("senderType") MessageSender senderType,
                 @Param("now") LocalDateTime now);

    /**
     * 後台紅點：每位司機一般對話有幾則某一方發的未讀訊息。沒有未讀的司機不會出現在結果裡。
     * select new 會直接呼叫 DriverMessageSummaryResponse 的 (Long, Long) 建構子。
     */
    @Query("""
            select new com.example.backend.dto.respones.DriverMessageSummaryResponse(m.driverId, count(m))
            from DriverMessagesEntity m
            where m.exceptionCaseId is null and m.senderType = :senderType and m.readAt is null
            group by m.driverId
            order by m.driverId
            """)
    List<DriverMessageSummaryResponse> countUnreadByDriver(@Param("senderType") MessageSender senderType);

    // ── 例外回報案件的對話：一件案件一串，對話仍屬於回報的司機 ──

    /** 案件對話第一次打開：最新的 50 則，由新到舊（Service 要 reversed()） */
    List<DriverMessagesEntity> findTop50ByDriverIdAndExceptionCaseIdOrderByIdDesc(
            Long driverId, Long exceptionCaseId);

    /** 案件對話的重連補抓：比 afterId 新的，由舊到新 */
    List<DriverMessagesEntity> findByDriverIdAndExceptionCaseIdAndIdGreaterThanOrderByIdAsc(
            Long driverId, Long exceptionCaseId, Long afterId);

    /** 把某件案件對話裡、某一方發的未讀訊息標成已讀；senderType 的意思同 markRead */
    @Modifying
    @Query("""
            update DriverMessagesEntity m set m.readAt = :now
            where m.driverId = :driverId and m.exceptionCaseId = :exceptionCaseId
              and m.senderType = :senderType and m.readAt is null
            """)
    int markCaseRead(@Param("driverId") Long driverId,
                     @Param("exceptionCaseId") Long exceptionCaseId,
                     @Param("senderType") MessageSender senderType,
                     @Param("now") LocalDateTime now);

    /**
     * 案件清單的紅點：每件案件有幾則某一方發的未讀訊息，沒有未讀的案件不會出現在結果裡。
     * exceptionCaseIds 不能是空的，呼叫端要先判斷。
     */
    @Query("""
            select m.exceptionCaseId as exceptionCaseId, count(m) as unreadCount
            from DriverMessagesEntity m
            where m.exceptionCaseId in :exceptionCaseIds and m.senderType = :senderType and m.readAt is null
            group by m.exceptionCaseId
            """)
    List<CaseUnreadCount> countUnreadByCase(@Param("exceptionCaseIds") Collection<Long> exceptionCaseIds,
                                            @Param("senderType") MessageSender senderType);

    /** countUnreadByCase 的一列；Spring Data 依 select 裡的別名對到同名的 getter */
    interface CaseUnreadCount {
        Long getExceptionCaseId();

        Long getUnreadCount();
    }
}
