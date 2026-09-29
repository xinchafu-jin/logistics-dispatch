package com.example.backend.controller;

import com.example.backend.constants.ExceptionStatus;
import com.example.backend.dto.request.DriverCaseCloseRequestDTO;
import com.example.backend.dto.request.DriverMessageRequestDTO;
import com.example.backend.dto.respones.AdminDriverCaseResponse;
import com.example.backend.dto.respones.DriverCaseOrdersResponse;
import com.example.backend.dto.respones.DriverMessageResponse;
import com.example.backend.service.DriverCaseService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 後台處理司機例外回報案件的 API：異常中心列清單、接收、結案；聊天室看案件對話、回覆、標已讀。
 *
 * <p>刻意掛在 /api/exceptions 底下：SecurityConfig 已經把 /api/exceptions/** 限定 ADMIN。
 * 改路徑前先確認新路徑也在 ADMIN 清單裡，否則會落到 anyRequest().authenticated()，司機的 token 也打得進來。
 * 路徑比 ExceptionController 的 /{exceptionCaseId}/close 多一段 driver-cases，兩邊不會搶同一個請求。</p>
 */
@RestController
@RequestMapping("/api/exceptions/driver-cases")
public class DriverCasesController {

    private final DriverCaseService driverCaseService;

    public DriverCasesController(DriverCaseService driverCaseService) {
        this.driverCaseService = driverCaseService;
    }

    /** 異常中心「司機回報」清單：不帶 status 是進行中的全部，status=CLOSED 是最近結案的 50 件。 */
    @GetMapping
    public List<AdminDriverCaseResponse> findCases(@RequestParam(required = false) ExceptionStatus status) {
        return driverCaseService.findForAdmin(status);
    }

    /** 接收案件；接收的是哪位管理員從 JWT 取。 */
    @PatchMapping("/{caseId}/accept")
    public AdminDriverCaseResponse accept(@AuthenticationPrincipal Jwt jwt, @PathVariable Long caseId) {
        return driverCaseService.accept(caseId, adminId(jwt));
    }

    /** 結案視窗要列的：案件路線上還沒結束的單，以及是不是一定要全部處理（路線日期已過）。 */
    @GetMapping("/{caseId}/unfinished-orders")
    public DriverCaseOrdersResponse findUnfinishedOrders(@PathVariable Long caseId) {
        return driverCaseService.findUnfinishedOrders(caseId);
    }

    /** 填處理結果結案，可一併把路線上送不完的單改期補送；處理人跟一般異常一樣記管理員名稱。 */
    @PatchMapping("/{caseId}/close")
    public AdminDriverCaseResponse close(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long caseId,
            @Valid @RequestBody DriverCaseCloseRequestDTO request
    ) {
        return driverCaseService.close(caseId, adminName(jwt), request.getResolution(), request.getRedeliverOrderIds());
    }

    /** 案件對話；afterId 省略時回最近 50 則，帶了只回比它新的。 */
    @GetMapping("/{caseId}/messages")
    public List<DriverMessageResponse> findMessages(
            @PathVariable Long caseId,
            @RequestParam(required = false) Long afterId
    ) {
        return driverCaseService.findMessagesForAdmin(caseId, afterId);
    }

    /** 在案件裡回覆司機；要先接收。 */
    @PostMapping("/{caseId}/messages")
    public DriverMessageResponse sendMessage(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long caseId,
            @Valid @RequestBody DriverMessageRequestDTO request
    ) {
        return driverCaseService.sendFromAdmin(caseId, adminId(jwt), request.getContent());
    }

    /** 把這件案件裡司機發的未讀訊息標成已讀，回傳這次標了幾筆。 */
    @PostMapping("/{caseId}/messages/read")
    public int markMessagesRead(@PathVariable Long caseId) {
        return driverCaseService.markReadByAdmin(caseId);
    }

    /** /api/exceptions/** 限定 ADMIN，所以這裡拿到的 userId 一定是 admin_users 的 id。 */
    private Long adminId(Jwt jwt) {
        Number userId = jwt.getClaim("userId");
        if (userId == null) {
            throw new IllegalArgumentException("JWT 缺少 userId");
        }
        return userId.longValue();
    }

    /** 跟 ExceptionController 一樣：優先用名稱，沒有才用帳號 */
    private String adminName(Jwt jwt) {
        String name = jwt.getClaimAsString("name");
        if (name != null && !name.isBlank()) {
            return name;
        }
        String account = jwt.getSubject();
        if (account == null || account.isBlank()) {
            throw new IllegalArgumentException("JWT 缺少主管識別資料");
        }
        return account;
    }
}
