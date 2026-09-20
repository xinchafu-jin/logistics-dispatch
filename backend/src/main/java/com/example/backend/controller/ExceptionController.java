package com.example.backend.controller;

import com.example.backend.constants.ExceptionStatus;
import com.example.backend.constants.ExceptionType;
import com.example.backend.dto.request.ExceptionResolutionRequestDTO;
import com.example.backend.dto.respones.ExceptionCaseResponse;
import com.example.backend.service.AuthService;
import com.example.backend.service.DeliveryExceptionService;
import com.example.backend.service.NoSignatureRecoveryService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import jakarta.validation.Valid;

import java.util.List;

/** 主管查詢並確認隔日配送異常的 API。 */
@RestController
@RequestMapping("/api/exceptions")
public class ExceptionController {

    private final DeliveryExceptionService deliveryExceptionService;
    private final NoSignatureRecoveryService noSignatureRecoveryService;

    public ExceptionController(
            DeliveryExceptionService deliveryExceptionService,
            NoSignatureRecoveryService noSignatureRecoveryService
    ) {
        this.deliveryExceptionService = deliveryExceptionService;
        this.noSignatureRecoveryService = noSignatureRecoveryService;
    }

    /** 查看隔日 06:00 後已進入確認區、尚未結案的配送異常。 */
    @GetMapping("/pending-confirmation")
    public List<ExceptionCaseResponse> findPendingConfirmation(
            @AuthenticationPrincipal Jwt jwt
    ) {
        reviewer(jwt);
        return deliveryExceptionService.findPendingConfirmation();
    }

    /** 確認異常並將後續訂單送入待排車；處理主管由 JWT 取得。 */
    @PatchMapping("/{exceptionCaseId}/confirm")
    public ExceptionCaseResponse confirm(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long exceptionCaseId
    ) {
        return deliveryExceptionService.confirm(exceptionCaseId, reviewer(jwt));
    }

    /** 主管查詢一般與無人簽收異常，可依狀態及類型過濾。 */
    @GetMapping
    public List<ExceptionCaseResponse> findAll(
            @RequestParam(required = false) ExceptionStatus status,
            @RequestParam(required = false) ExceptionType type
    ) {
        return deliveryExceptionService.findAll(status, type);
    }

    /** 主管修正誤按無人簽收：取消尚未核准的隔日單，恢復原單配送。 */
    @PatchMapping("/{exceptionCaseId}/restore-delivery")
    public ExceptionCaseResponse restoreDelivery(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long exceptionCaseId
    ) {
        return noSignatureRecoveryService.restoreDelivery(exceptionCaseId, reviewer(jwt));
    }

    /** 主管填寫處理結果並結案一般異常。 */
    @PatchMapping("/{exceptionCaseId}/close")
    public ExceptionCaseResponse close(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long exceptionCaseId,
            @Valid @RequestBody ExceptionResolutionRequestDTO request
    ) {
        return deliveryExceptionService.closeGeneral(
                exceptionCaseId, reviewer(jwt), request.getResolution());
    }

    private String reviewer(Jwt jwt) {
        if (jwt == null) {
            throw new IllegalArgumentException("缺少主管身分資訊");
        }
        String role = jwt.getClaimAsString("role");
        if (!AuthService.ROLE_ADMIN.equals(role)) {
            throw new IllegalArgumentException("只有物流主管可以操作配送異常");
        }
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
