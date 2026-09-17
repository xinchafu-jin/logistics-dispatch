package com.example.backend.controller;

import com.example.backend.dto.request.DriverAccountApplicationRequest;
import com.example.backend.dto.respones.DriverAccountApplicationResponse;
import com.example.backend.service.DriverAccountApplicationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 司機帳號申請及主管審核 API。 */
@RestController
@RequestMapping("/api/driver-account-applications")
public class DriverAccountApplicationController {

    private final DriverAccountApplicationService applicationService;

    public DriverAccountApplicationController(
            DriverAccountApplicationService applicationService
    ) {
        this.applicationService = applicationService;
    }

    /** 尚未登入的申請人送出司機帳號申請。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DriverAccountApplicationResponse submit(
            @Valid @RequestBody DriverAccountApplicationRequest request
    ) {
        return applicationService.submit(request);
    }

    /** 主管首頁顯示的待審核數量。 */
    @GetMapping("/pending/count")
    public Map<String, Long> countPending() {
        return Map.of("count", applicationService.countPending());
    }

    /** 主管查看待審核申請，依送件時間由舊到新排列。 */
    @GetMapping("/pending")
    public List<DriverAccountApplicationResponse> findPending() {
        return applicationService.findPending();
    }

    /** 主管核准申請；審核人由 JWT 取得。 */
    @PatchMapping("/{applicationId}/approve")
    public DriverAccountApplicationResponse approve(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long applicationId
    ) {
        return applicationService.approve(applicationId, reviewer(jwt));
    }

    /** 主管拒絕申請；審核人由 JWT 取得。 */
    @PatchMapping("/{applicationId}/reject")
    public DriverAccountApplicationResponse reject(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long applicationId,
            @Valid @RequestBody RejectionRequest request
    ) {
        return applicationService.reject(applicationId, reviewer(jwt), request.getReason());
    }

    private String reviewer(Jwt jwt) {
        if (jwt == null) {
            throw new IllegalArgumentException("缺少主管身分資訊");
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

    public static class RejectionRequest {

        @NotBlank(message = "拒絕原因不能為空")
        @Size(max = 500, message = "拒絕原因不能超過 500 字元")
        private String reason;

        public String getReason() {
            return reason;
        }

        public void setReason(String reason) {
            this.reason = reason;
        }
    }
}
