package com.example.backend.controller;

import com.example.backend.dto.respones.DriverPortalResponse;
import com.example.backend.service.AuthService;
import com.example.backend.service.DriverPortalService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 司機端前台 API。
 *
 * <p>Controller 只負責處理 HTTP 請求與從 JWT 取得登入者身分；
 * 資料查詢、組裝與業務邏輯由 {@link DriverPortalService} 處理。</p>
 */
@RestController
@RequestMapping("/api/driver")
public class DriverPortalController {

    private final DriverPortalService driverPortalService;

    public DriverPortalController(DriverPortalService driverPortalService) {
        this.driverPortalService = driverPortalService;
    }

    /** 個資頁：登入司機自己的基本資料與固定上班時間。 */
    @GetMapping("/profile")
    public ResponseEntity<DriverPortalResponse.ProfileResponse> profile(
            @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(driverPortalService.getProfile(driverIdOf(jwt)));
    }

    //若任務、地圖或班表回傳空陣列 []，請確認資料庫的 orders 資料符合

    /** 任務頁：指定日期已指派給登入司機的訂單。 */
    @GetMapping("/tasks")
    public ResponseEntity<List<DriverPortalResponse.TaskResponse>> tasks(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(driverPortalService.getTasks(driverIdOf(jwt), date));
    }

    /** 地圖頁：指定日期的倉庫、配送路線與門市座標。 */
    @GetMapping("/map")
    public ResponseEntity<List<DriverPortalResponse.RouteMapResponse>> map(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(driverPortalService.getMap(driverIdOf(jwt), date));
    }

    /** 班表頁：指定日期區間的任務班表。 */
    @GetMapping("/schedule")
    public ResponseEntity<List<DriverPortalResponse.ScheduleDayResponse>> schedule(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam("from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam("to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(driverPortalService.getSchedule(driverIdOf(jwt), from, to));
    }

    /**
     * 不接受前端傳入 driverId，固定使用 JWT 的 userId，
     * 避免司機改查詢參數後讀取到其他司機的任務。
     */
    private Long driverIdOf(Jwt jwt) {
        if (!AuthService.ROLE_DRIVER.equals(jwt.getClaimAsString("role"))) {
            throw new IllegalArgumentException("此 API 僅限司機帳號使用");
        }

        Number userId = jwt.getClaim("userId");
        if (userId == null) {
            throw new IllegalArgumentException("登入權杖缺少司機身分資訊");
        }
        return userId.longValue();
    }
}
