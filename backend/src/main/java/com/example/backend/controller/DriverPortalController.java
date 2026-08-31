package com.example.backend.controller;

import com.example.backend.dto.request.AttendanceRecordDTO;
import com.example.backend.dto.request.DriverShiftDTO;
import com.example.backend.dto.request.GpsPingDTO;
import com.example.backend.service.AttendanceService;
import com.example.backend.service.DriverScheduleService;
import com.example.backend.service.GpsPingsService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 司機端使用的出勤、GPS 與班表 API。
 * 司機 ID 一律從 JWT 的 userId 取得，避免前端操作其他司機的資料。
 */
@RestController
@RequestMapping("/api/driver")
public class DriverPortalController {

    private final AttendanceService attendanceService;
    private final DriverScheduleService driverScheduleService;
    private final GpsPingsService gpsPingsService;

    public DriverPortalController(
            AttendanceService attendanceService,
            DriverScheduleService driverScheduleService,
            GpsPingsService gpsPingsService
    ) {
        this.attendanceService = attendanceService;
        this.driverScheduleService = driverScheduleService;
        this.gpsPingsService = gpsPingsService;
    }

    /** 查詢今天的打卡、休息及 GPS 上傳狀態。 */
    @GetMapping("/attendance/today")
    public AttendanceRecordDTO findTodayAttendance(@AuthenticationPrincipal Jwt jwt) {
        return attendanceService.findToday(driverId(jwt));
    }

    /** 司機上班打卡；今天必須有已發布的上班班次。 */
    @PostMapping("/attendance/clock-in")
    public AttendanceRecordDTO clockIn(@AuthenticationPrincipal Jwt jwt) {
        return attendanceService.clockIn(driverId(jwt));
    }

    /** 開始一次休息。 */
    @PostMapping("/attendance/break")
    public AttendanceRecordDTO startBreak(@AuthenticationPrincipal Jwt jwt) {
        return attendanceService.startBreak(driverId(jwt));
    }

    /** 司機下班打卡。 */
    @PostMapping("/attendance/clock-out")
    public AttendanceRecordDTO clockOut(@AuthenticationPrincipal Jwt jwt) {
        return attendanceService.clockOut(driverId(jwt));
    }

    /** 上傳目前 GPS 座標；driverId 與 timestamp 由後端決定。 */
    @PostMapping("/gps")
    public GpsPingDTO saveGps(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody GpsPingDTO dto
    ) {
        return gpsPingsService.savePing(driverId(jwt), dto);
    }

    /** 查詢登入司機指定日期範圍內、已發布的班表。 */
    @GetMapping("/shifts")
    public List<DriverShiftDTO> findPublishedShifts(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        return driverScheduleService.findPublishedForDriver(driverId(jwt), from, to);
    }

    /** 從登入 Token 取得資料庫中的司機 ID。 */
    private Long driverId(Jwt jwt) {
        Number userId = jwt.getClaim("userId");
        if (userId == null) {
            throw new IllegalArgumentException("JWT 缺少 userId");
        }
        return userId.longValue();
    }
}
