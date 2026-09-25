package com.example.backend.controller;

import com.example.backend.dto.request.LeaveDecisionRequestDTO;
import com.example.backend.dto.request.LeaveTypeCorrectionDTO;
import com.example.backend.dto.request.PlannedPartialLeaveRequestDTO;
import com.example.backend.dto.respones.DriverLeaveResponse;
import com.example.backend.dto.respones.DriverLeaveHistoryResponse;
import com.example.backend.dto.respones.DriverMonthlyLeaveSummaryResponse;
import com.example.backend.service.DriverLeaveRequestService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
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

import java.time.YearMonth;
import java.util.List;

@RestController
@RequestMapping("/api/leave-requests")
public class DriverLeaveRequestController {
    private final DriverLeaveRequestService service;

    public DriverLeaveRequestController(DriverLeaveRequestService service) {
        this.service = service;
    }

    @GetMapping("/pending")
    public List<DriverLeaveResponse> findPending() {
        return service.findPending();
    }

    /** 主管班表左側司機名稱 hover 時，依月份取得狀態表。 */
    @GetMapping("/drivers/{driverId}/monthly")
    public DriverMonthlyLeaveSummaryResponse findMonthly(
            @PathVariable Long driverId,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth month
    ) {
        return service.findMonthly(driverId, month);
    }

    /** 主管稽核單一請假單從送出到所有修正的完整歷程。 */
    @GetMapping("/{id}/history")
    public List<DriverLeaveHistoryResponse> findHistory(@PathVariable Long id) {
        return service.findHistory(id);
    }

    @PatchMapping("/{id}/approve")
    public DriverLeaveResponse approve(
            @PathVariable Long id,
            @Valid @RequestBody LeaveDecisionRequestDTO request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return service.approve(id, request.getLeaveType(), request.getReason(), adminId(jwt), jwt.getSubject());
    }

    @PatchMapping("/{id}/reject")
    public DriverLeaveResponse reject(
            @PathVariable Long id,
            @Valid @RequestBody LeaveDecisionRequestDTO request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return service.reject(id, request.getReason(), adminId(jwt), jwt.getSubject());
    }

    @PatchMapping("/{id}/type")
    public DriverLeaveResponse correctType(
            @PathVariable Long id,
            @Valid @RequestBody LeaveTypeCorrectionDTO request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return service.correctType(id, request.getLeaveType(), request.getReason(), adminId(jwt), jwt.getSubject());
    }

    @PostMapping("/planned-partial")
    public DriverLeaveResponse createPlannedPartial(
            @Valid @RequestBody PlannedPartialLeaveRequestDTO request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return service.createPlannedPartial(request, adminId(jwt), jwt.getSubject());
    }

    private Long adminId(Jwt jwt) {
        Number userId = jwt.getClaim("userId");
        if (userId == null) {
            throw new IllegalArgumentException("JWT 缺少 userId");
        }
        return userId.longValue();
    }
}
