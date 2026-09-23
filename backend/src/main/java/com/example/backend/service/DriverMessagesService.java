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
import java.util.List;

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
            message = driverMessagesDAO.findTop50ByDriverIdOrderByIdDesc(driverId).reversed();
        } else {
            message = driverMessagesDAO.findByDriverIdAndIdGreaterThanOrderByIdAsc(driverId, afterId);
        }
        List<DriverMessageResponse> driverMessageResponses = new ArrayList<>();
        for (DriverMessagesEntity entity : message) {
            driverMessageResponses.add(toDriverMessageResponse(entity));
        }
        return driverMessageResponses;
    }

    public DriverMessageResponse sendFromDriver(Long driverId, String content) {
        return save(driverId, MessageSender.DRIVER, null, content);
    }

    public DriverMessageResponse sendFromAdmin(Long driverId, Long adminId, String content) {
        return save(driverId, MessageSender.ADMIN, adminId, content);
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

    private DriverMessageResponse save(Long driverId, MessageSender sender, Long adminId, String content) {
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

    private DriverMessageResponse toDriverMessageResponse(DriverMessagesEntity entity) {
        DriverMessageResponse response = new DriverMessageResponse();
        response.setId(entity.getId());
        response.setDriverId(entity.getDriverId());
        response.setSenderType(entity.getSenderType());
        response.setContent(entity.getContent());
        response.setCreatedAt(entity.getCreatedAt());
        response.setReadAt(entity.getReadAt());
        return response;
    }
}
