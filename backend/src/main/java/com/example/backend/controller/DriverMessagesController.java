package com.example.backend.controller;

import com.example.backend.dto.request.DriverMessageRequestDTO;
import com.example.backend.dto.respones.DriverMessageResponse;
import com.example.backend.dto.respones.DriverMessageSummaryResponse;
import com.example.backend.service.DriverMessagesService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 後台（調度中心）的司機聊天室 API。
 *
 * <p>刻意掛在 /api/drivers 底下：SecurityConfig 已經把 /api/drivers/** 限定 ADMIN，
 * 不用另外加規則。改路徑前先確認新路徑也在 ADMIN 清單裡，否則會落到 anyRequest().authenticated()，
 * 司機的 token 也打得進來。</p>
 */
@RestController
@RequestMapping("/api/drivers")
public class DriverMessagesController {

    private final DriverMessagesService driverMessagesService;

    public DriverMessagesController(DriverMessagesService driverMessagesService) {
        this.driverMessagesService = driverMessagesService;
    }

    /** 取得指定司機的對話。afterId 省略時回最近 50 則；帶了只回比它新的。 */
    @GetMapping("/{driverId}/messages")
    public List<DriverMessageResponse> findMessages(
            @PathVariable Long driverId,
            @RequestParam(required = false) Long afterId
    ) {
        return driverMessagesService.findMessages(driverId, afterId);
    }

    /**
     * 管理員回覆指定司機。司機是誰從網址取，回覆的是哪位管理員從 JWT 取（寫進 sender_admin_id 供追查）。
     * 回傳存好的那一則（含 id），前端直接放進清單。
     */
    @PostMapping("/{driverId}/messages")
    public DriverMessageResponse sendMessage(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long driverId,
            @Valid @RequestBody DriverMessageRequestDTO request
    ) {
        return driverMessagesService.sendFromAdmin(driverId, adminId(jwt), request.getContent());
    }

    /** 把這位司機發的未讀訊息標成已讀，回傳這次標了幾筆。已讀是所有管理員共用的。 */
    @PostMapping("/{driverId}/messages/read")
    public int markMessagesRead(@PathVariable Long driverId) {
        return driverMessagesService.markReadByAdmin(driverId);
    }

    /**
     * 紅點用：每位司機有幾則未讀。只列有未讀的司機，前端拿 driverId 對到司機名單。
     *
     * <p>路徑是 /messages/summary（兩段），不會跟 DriverController 的 GET /{id}（一段）搶同一個請求。</p>
     */
    @GetMapping("/messages/summary")
    public List<DriverMessageSummaryResponse> findUnreadSummary() {
        return driverMessagesService.findUnreadSummary();
    }

    /** /api/drivers/** 限定 ADMIN，所以這裡拿到的 userId 一定是 admin_users 的 id。 */
    private Long adminId(Jwt jwt) {
        Number userId = jwt.getClaim("userId");
        if (userId == null) {
            throw new IllegalArgumentException("JWT 缺少 userId");
        }
        return userId.longValue();
    }
}
