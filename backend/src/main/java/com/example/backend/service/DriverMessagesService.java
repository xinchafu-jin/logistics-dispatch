package com.example.backend.service;

import com.example.backend.constants.MessageSender;
import com.example.backend.dao.DriverMessagesDAO;
import com.example.backend.dao.DriversDAO;
import com.example.backend.dto.respones.DriverMessagePushResponse;
import com.example.backend.dto.respones.DriverMessageResponse;
import com.example.backend.dto.respones.DriverMessageSummaryResponse;
import com.example.backend.entity.DriverMessagesEntity;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.example.backend.constants.ValidMsg.DRIVER_MESSAGE_CONTENT_MAX_LENGTH;
import static com.example.backend.constants.ValidMsg.DRIVER_MESSAGE_CONTENT_REQUIRED;

@Service
@Transactional
public class DriverMessagesService {
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final int MAX_CONTENT_LENGTH = 1000;
    private final DriverMessagesDAO driverMessagesDAO;
    private final DriversDAO driversDAO;
    // 只負責「發事件」，真正推播由 DriverMessagesPushService 在交易 commit 後執行
    private final ApplicationEventPublisher eventPublisher;

    public DriverMessagesService(DriverMessagesDAO driverMessagesDAO, DriversDAO driversDAO,
                                 ApplicationEventPublisher eventPublisher) {
        this.driverMessagesDAO = driverMessagesDAO;
        this.driversDAO = driversDAO;
        this.eventPublisher = eventPublisher;
    }

    @Transactional(readOnly = true)
    public List<DriverMessageResponse> findMessages(Long driverId, Long afterId) {
        ensureDriverExists(driverId);
        List<DriverMessagesEntity> message;
        if (afterId == null) {
            // 查詢是由新到舊取前 50 則，畫面要由舊到新，所以反轉
            message = driverMessagesDAO.findTop50ByDriverIdAndExceptionCaseIdIsNullOrderByIdDesc(driverId).reversed();
        } else {
            message = driverMessagesDAO.findByDriverIdAndExceptionCaseIdIsNullAndIdGreaterThanOrderByIdAsc(driverId, afterId);
        }
        return toDriverMessageResponses(message);
    }

    public DriverMessageResponse sendFromDriver(Long driverId, String content) {
        return save(driverId, null, MessageSender.DRIVER, null, content);
    }

    public DriverMessageResponse sendFromAdmin(Long driverId, Long adminId, String content) {
        return save(driverId, null, MessageSender.ADMIN, adminId, content);
    }

    // ── 例外回報案件的對話 ──
    // 由 DriverCaseService 呼叫：案件是不是這位司機的、結案了沒、接收了沒，都在那邊先檢查過，這裡只管訊息本身

    /** 某件案件的對話；afterId 的用法跟一般對話一樣 */
    @Transactional(readOnly = true)
    public List<DriverMessageResponse> findCaseMessages(Long driverId, Long exceptionCaseId, Long afterId) {
        List<DriverMessagesEntity> message;
        if (afterId == null) {
            message = driverMessagesDAO.findTop50ByDriverIdAndExceptionCaseIdOrderByIdDesc(driverId, exceptionCaseId).reversed();
        } else {
            message = driverMessagesDAO.findByDriverIdAndExceptionCaseIdAndIdGreaterThanOrderByIdAsc(
                    driverId, exceptionCaseId, afterId);
        }
        return toDriverMessageResponses(message);
    }

    /** 在某件案件的對話留言；sender 是 ADMIN 時要帶 adminId */
    public DriverMessageResponse sendCaseMessage(Long driverId, Long exceptionCaseId, MessageSender sender,
                                                 Long adminId, String content) {
        return save(driverId, exceptionCaseId, sender, adminId, content);
    }

    /** 把某件案件對話裡 readSenderType 發的訊息標成已讀；有標到才推 READ（帶 exceptionCaseId） */
    public int markCaseRead(Long driverId, Long exceptionCaseId, MessageSender readSenderType) {
        LocalDateTime now = LocalDateTime.now(TAIPEI);
        int updated = driverMessagesDAO.markCaseRead(driverId, exceptionCaseId, readSenderType, now);
        if (updated > 0) {
            eventPublisher.publishEvent(
                    DriverMessagePushResponse.ofCaseRead(driverId, exceptionCaseId, readSenderType, now));
        }
        return updated;
    }

    /** 每件案件有幾則 senderType 發的未讀訊息（案件 id → 則數）；沒有未讀的案件不在 Map 裡 */
    @Transactional(readOnly = true)
    public Map<Long, Long> countUnreadByCase(Collection<Long> exceptionCaseIds, MessageSender senderType) {
        Map<Long, Long> unread = new HashMap<>();
        // IN 後面放空清單在不同資料庫的行為不一樣，沒有案件就不查
        if (exceptionCaseIds.isEmpty()) {
            return unread;
        }
        for (DriverMessagesDAO.CaseUnreadCount row : driverMessagesDAO.countUnreadByCase(exceptionCaseIds, senderType)) {
            unread.put(row.getExceptionCaseId(), row.getUnreadCount());
        }
        return unread;
    }

    /** 司機讀的是調度中心的回覆，所以標的是 ADMIN 發的訊息。回傳這次標了幾筆。 */
    public int markReadByDriver(Long driverId) {
        ensureDriverExists(driverId);
        return markRead(driverId, MessageSender.ADMIN);
    }

    /** 管理員讀的是司機發的訊息。已讀是所有管理員共用的，誰點開都算。回傳這次標了幾筆。 */
    public int markReadByAdmin(Long driverId) {
        ensureDriverExists(driverId);
        return markRead(driverId, MessageSender.DRIVER);
    }

    /** 後台紅點：每位司機還沒被管理員讀的訊息數；沒有未讀的司機不會出現在清單裡。 */
    @Transactional(readOnly = true)
    public List<DriverMessageSummaryResponse> findUnreadSummary() {
        return driverMessagesDAO.countUnreadByDriver(MessageSender.DRIVER);
    }

    /** exceptionCaseId 是 null＝一般對話 */
    private DriverMessageResponse save(Long driverId, Long exceptionCaseId, MessageSender sender, Long adminId,
                                       String content) {
        ensureDriverExists(driverId);
        String text;
        if (content == null) {
            text = "";
        } else {
            text = content.strip();
        }
        if (text.isEmpty()) {
            throw new IllegalArgumentException(DRIVER_MESSAGE_CONTENT_REQUIRED);
        }
        if (text.length() > MAX_CONTENT_LENGTH) {
            throw new IllegalArgumentException(DRIVER_MESSAGE_CONTENT_MAX_LENGTH);
        }
        DriverMessagesEntity entity = new DriverMessagesEntity();
        entity.setDriverId(driverId);
        entity.setExceptionCaseId(exceptionCaseId);
        entity.setSenderType(sender);
        entity.setSenderAdminId(adminId);
        entity.setContent(text);
        // 用伺服器時間，不收前端傳的時間
        entity.setCreatedAt(LocalDateTime.now(TAIPEI));
        DriverMessageResponse response = toDriverMessageResponse(driverMessagesDAO.save(entity));
        eventPublisher.publishEvent(DriverMessagePushResponse.ofMessage(response));
        return response;
    }

    /** 兩個 markReadBy... 共用：標已讀，有標到才發事件（沒東西可標就不用吵醒前端）。 */
    private int markRead(Long driverId, MessageSender readSenderType) {
        LocalDateTime now = LocalDateTime.now(TAIPEI);
        int updated = driverMessagesDAO.markRead(driverId, readSenderType, now);
        if (updated > 0) {
            eventPublisher.publishEvent(DriverMessagePushResponse.ofRead(driverId, readSenderType, now));
        }
        return updated;
    }

    private void ensureDriverExists(Long driverId) {
        if (!driversDAO.existsById(driverId)) {
            throw new EntityNotFoundException("找不到司機，ID：" + driverId);
        }
    }

    private List<DriverMessageResponse> toDriverMessageResponses(List<DriverMessagesEntity> messages) {
        List<DriverMessageResponse> driverMessageResponses = new ArrayList<>();
        for (DriverMessagesEntity entity : messages) {
            driverMessageResponses.add(toDriverMessageResponse(entity));
        }
        return driverMessageResponses;
    }

    private DriverMessageResponse toDriverMessageResponse(DriverMessagesEntity entity) {
        DriverMessageResponse response = new DriverMessageResponse();
        response.setId(entity.getId());
        response.setDriverId(entity.getDriverId());
        response.setSenderType(entity.getSenderType());
        response.setContent(entity.getContent());
        response.setCreatedAt(entity.getCreatedAt());
        response.setReadAt(entity.getReadAt());
        response.setExceptionCaseId(entity.getExceptionCaseId());
        return response;
    }
}
