package com.example.backend.controller;

import com.example.backend.dto.respones.EmergencyLeaveReplacementCandidateResponse;
import com.example.backend.dto.respones.EmergencyLeaveResponse;
import com.example.backend.service.EmergencyLeaveService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 主管審核上班中臨時請假並完成路線交接。 */
@RestController
@RequestMapping("/api/emergency-leave-requests")
public class EmergencyLeaveController {
    private final EmergencyLeaveService service;

    public EmergencyLeaveController(EmergencyLeaveService service) {
        this.service = service;
    }

    @GetMapping("/pending")
    public List<EmergencyLeaveResponse> pending() {
        return service.findPending();
    }

    @GetMapping("/{id}/replacement-candidates")
    public List<EmergencyLeaveReplacementCandidateResponse> replacementCandidates(@PathVariable Long id) {
        return service.replacementCandidates(id);
    }

    @PatchMapping("/{id}/approve")
    public EmergencyLeaveResponse approve(
            @PathVariable Long id,
            @Valid @RequestBody ApprovalRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return service.approve(id, request.getReplacementDriverId(), jwt.getSubject());
    }

    @PatchMapping("/{id}/reject")
    public EmergencyLeaveResponse reject(
            @PathVariable Long id,
            @Valid @RequestBody RejectionRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return service.reject(id, request.getReason(), jwt.getSubject());
    }

    public static class ApprovalRequest {
        @NotNull(message = "請選擇接手司機")
        private Long replacementDriverId;

        public Long getReplacementDriverId() { return replacementDriverId; }
        public void setReplacementDriverId(Long replacementDriverId) { this.replacementDriverId = replacementDriverId; }
    }

    public static class RejectionRequest {
        @NotBlank(message = "請填寫拒絕原因")
        private String reason;

        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
    }
}
