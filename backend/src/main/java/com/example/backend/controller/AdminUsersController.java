package com.example.backend.controller;

import com.example.backend.dto.request.AdminUsersDTO;
import com.example.backend.dto.request.AiApiKeyDTO;
import com.example.backend.dto.respones.AiApiKeyStatusResponse;
import com.example.backend.service.AdminUsersService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin-users")
public class AdminUsersController {

    private final AdminUsersService adminUsersService;

    public AdminUsersController(AdminUsersService adminUsersService) {
        this.adminUsersService = adminUsersService;
    }

    @PostMapping
    public ResponseEntity<AdminUsersDTO> create(@Valid @RequestBody AdminUsersDTO dto) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(adminUsersService.create(dto));
    }

    /** 查詢自己的 AI API Key 設定狀態。 */
    @GetMapping("/me/ai-api-key")
    public AiApiKeyStatusResponse getAiApiKeyStatus(@AuthenticationPrincipal Jwt jwt) {
        return adminUsersService.getAiApiKeyStatus(adminId(jwt));
    }

    /** 新增或更新自己的 AI API Key。 */
    @PutMapping("/me/ai-api-key")
    public AiApiKeyStatusResponse saveAiApiKey(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody AiApiKeyDTO dto) {
        return adminUsersService.saveAiApiKey(adminId(jwt), dto.getApiKey());
    }

    /** 移除自己的 AI API Key。 */
    @DeleteMapping("/me/ai-api-key")
    public ResponseEntity<Void> deleteAiApiKey(@AuthenticationPrincipal Jwt jwt) {
        adminUsersService.deleteAiApiKey(adminId(jwt));
        return ResponseEntity.noContent().build();
    }

    /**
     * 從登入 Token 取得資料庫中的主管 ID。
     *
     * <p>JWT 的 userId 不分主管或司機（兩張表各自編號），這支 API 只靠
     * SecurityConfig 對 /api/admin-users/** 限定 ADMIN 來保證拿到的是主管 ID，
     * 所以不能搬到沒有限定角色的路徑。</p>
     */
    private Long adminId(Jwt jwt) {
        Number userId = jwt.getClaim("userId");
        if (userId == null) {
            throw new IllegalArgumentException("JWT 缺少 userId");
        }
        return userId.longValue();
    }
}
