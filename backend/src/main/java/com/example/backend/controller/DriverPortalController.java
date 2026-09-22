package com.example.backend.controller;

import com.example.backend.dto.request.*;
import com.example.backend.dto.respones.DeliveryRecordResponse;
import com.example.backend.dto.respones.DriverMessageResponse;
import com.example.backend.dto.respones.DriverTasksResponse;
import com.example.backend.dto.respones.GPSRouteResponse;
import com.example.backend.dto.respones.MileageLogResponse;
import com.example.backend.service.*;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
import java.util.Map;

/**
 * 司機端使用的出勤、GPS 與班表 API。
 * 司機 ID 一律從 JWT 的 userId 取得，避免前端操作其他司機的資料。
 */
@RestController
@RequestMapping("/api/driver")
public class DriverPortalController {

    private final AttendanceService attendanceService;
    private final DeliveryService deliveryService;
    private final DriverScheduleService driverScheduleService;
    private final DriverTasksService driverTasksService;
    private final GpsPingsService gpsPingsService;
    private final MileageLogsService mileageLogsService;
    private final GPSRouteService gpsRouteService;
    private final DriverMessagesService driverMessagesService;

    public DriverPortalController(
            AttendanceService attendanceService,
            DeliveryService deliveryService,
            DriverScheduleService driverScheduleService,
            DriverTasksService driverTasksService,
            GpsPingsService gpsPingsService,
            MileageLogsService mileageLogsService,
            GPSRouteService gpsRouteService,
            DriverMessagesService driverMessagesService
    ) {
        this.attendanceService = attendanceService;
        this.deliveryService = deliveryService;
        this.driverScheduleService = driverScheduleService;
        this.driverTasksService = driverTasksService;
        this.gpsPingsService = gpsPingsService;
        this.mileageLogsService = mileageLogsService;
        this.gpsRouteService = gpsRouteService;
        this.driverMessagesService = driverMessagesService;
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

    /** 取得登入司機今天已發布的配送任務。 */
    @GetMapping("/tasks/today")
    public DriverTasksResponse findTodayTasks(@AuthenticationPrincipal Jwt jwt) {
        return driverTasksService.findToday(driverId(jwt));
    }

    /** 記錄司機抵達門市的時間。 */
    @PostMapping("/arrive")
    public DeliveryRecordResponse arrive(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ArriveRequestDTO request) {
        return deliveryService.arrive(driverId(jwt), request);
    }

    /** 寫入交貨結果、箱數、備註及照片。 */
    @PostMapping("/deliver")
    public DeliveryRecordResponse deliver(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody DeliverRequestDTO request) {
        return deliveryService.deliver(driverId(jwt), request);
    }

    /** 登記無人簽收並保留現場照片。 */
    @PostMapping("/no-signature")
    public DeliveryRecordResponse noSignature(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody NoSignatureRequestDTO request) {
        return deliveryService.noSignature(driverId(jwt), request);
    }

    /** 司機回報配送途中發生的異常。 */
    @PostMapping("/exception")
    public ResponseEntity<Map<String, Object>> reportException(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody Map<String, Object> request) {
        driverId(jwt);
        return pending("POST /api/driver/exception");
    }

    /** 記錄今日出車時的里程表讀數。 */
    @PostMapping("/mileage/start")
    public MileageLogResponse startMileage(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody MileageRequestDTO request) {
        return mileageLogsService.start(driverId(jwt), request);
    }

    /** 記錄今日收工時的里程表讀數。 */
    @PostMapping("/mileage/end")
    public MileageLogResponse endMileage(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody MileageRequestDTO request) {
        return mileageLogsService.end(driverId(jwt), request);
    }

    /**
     * 取得自己與調度中心的對話。afterId 省略時回最近 50 則；帶了只回比它新的（輪詢、重連補抓用）。
     * 只能看自己的：driverId 從 JWT 取，前端沒有地方可以指定別人。
     */
    @GetMapping("/messages")
    public List<DriverMessageResponse> findMessages(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) Long afterId
    ) {
        return driverMessagesService.findMessages(driverId(jwt), afterId);
    }

    /**
     * 司機發訊息給調度中心。只收內容：對話屬於誰、誰發的、時間都由後端決定。
     * 回傳存好的那一則（含 id），前端直接放進清單，並用 id 跟之後的輪詢結果去重。
     */
    @PostMapping("/messages")
    public DriverMessageResponse sendMessage(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody DriverMessageRequestDTO request
    ) {
        return driverMessagesService.sendFromDriver(driverId(jwt), request.getContent());
    }

    /**
     * 把調度中心的回覆標成已讀，回傳這次標了幾筆。
     * 只在司機停在聊天分頁時呼叫；在別的分頁輪詢未讀數時不要呼叫，否則紅點永遠看不到。
     */
    @PostMapping("/messages/read")
    public int markMessagesRead(@AuthenticationPrincipal Jwt jwt) {
        return driverMessagesService.markReadByDriver(driverId(jwt));
    }

    /** 從登入 Token 取得資料庫中的司機 ID。 */
    private Long driverId(Jwt jwt) {
        Number userId = jwt.getClaim("userId");
        if (userId == null) {
            throw new IllegalArgumentException("JWT 缺少 userId");
        }
        return userId.longValue();
    }

    private ResponseEntity<Map<String, Object>> pending(String api) {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(Map.of(
                "success", false,
                "message", "Controller 已建立，尚未接上 Service",
                "api", api
        ));
    }

    @PostMapping("/route")
    public GPSRouteResponse find(@Valid @RequestBody GPSRouteDTO dto) {
        return gpsRouteService.findRoute(dto);
    }

}
