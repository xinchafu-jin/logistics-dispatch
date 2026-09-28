package com.example.backend.controller;

import com.example.backend.dto.request.*;
import com.example.backend.dto.respones.DeliveryRecordResponse;
import com.example.backend.dto.respones.DriverMessageResponse;
import com.example.backend.dto.respones.DriverLeaveResponse;
import com.example.backend.dto.respones.DriverLeaveBatchResponse;
import com.example.backend.dto.respones.DriverLeaveHistoryResponse;
import com.example.backend.dto.respones.DriverTasksResponse;
import com.example.backend.dto.respones.GPSRouteResponse;
import com.example.backend.dto.respones.MileageLogResponse;
import com.example.backend.dto.respones.EmergencyLeaveResponse;
import com.example.backend.dto.respones.ExceptionCaseResponse;
import com.example.backend.dto.respones.LoadingResponse;
import com.example.backend.dto.respones.PhotoUploadResponse;
import com.example.backend.service.*;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.List;

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
    private final DriverExceptionService driverExceptionService;
    private final GPSRouteService gpsRouteService;
    private final DriversService driversService;
    private final EmergencyLeaveService emergencyLeaveService;
    private final DeliveryPhotoStorageService deliveryPhotoStorageService;
    private final LeaveEvidencePhotoStorageService leaveEvidencePhotoStorageService;
    private final DriverMessagesService driverMessagesService;
    private final DriverLeaveRequestService driverLeaveRequestService;

    public DriverPortalController(
            AttendanceService attendanceService,
            DeliveryService deliveryService,
            DriverScheduleService driverScheduleService,
            DriverTasksService driverTasksService,
            GpsPingsService gpsPingsService,
            MileageLogsService mileageLogsService,
            DriverExceptionService driverExceptionService,
            GPSRouteService gpsRouteService,
            DriversService driversService,
            EmergencyLeaveService emergencyLeaveService,
            DeliveryPhotoStorageService deliveryPhotoStorageService,
            LeaveEvidencePhotoStorageService leaveEvidencePhotoStorageService,
            DriverMessagesService driverMessagesService,
            DriverLeaveRequestService driverLeaveRequestService
    ) {
        this.attendanceService = attendanceService;
        this.deliveryService = deliveryService;
        this.driverScheduleService = driverScheduleService;
        this.driverTasksService = driverTasksService;
        this.gpsPingsService = gpsPingsService;
        this.mileageLogsService = mileageLogsService;
        this.driverExceptionService = driverExceptionService;
        this.gpsRouteService = gpsRouteService;
        this.driversService = driversService;
        this.emergencyLeaveService = emergencyLeaveService;
        this.deliveryPhotoStorageService = deliveryPhotoStorageService;
        this.leaveEvidencePhotoStorageService = leaveEvidencePhotoStorageService;
        this.driverMessagesService = driverMessagesService;
        this.driverLeaveRequestService = driverLeaveRequestService;
    }

    /** 取得目前登入司機的基本資料與大頭照網址。 */
    @GetMapping("/profile")
    public DriversDTO findProfile(@AuthenticationPrincipal Jwt jwt) {
        return driversService.findById(driverId(jwt));
    }

    /** 由登入中的司機上傳或更換自己的大頭照。 */
    @PostMapping(value = "/profile/photo", consumes = "multipart/form-data")
    public DriversDTO uploadProfilePhoto(
            @AuthenticationPrincipal Jwt jwt,
            @RequestPart("file") MultipartFile file
    ) {
        return driversService.updateProfilePhoto(driverId(jwt), file);
    }

    /** 查詢今天的打卡、休息、正常工時、加班工時、總工時及 GPS 上傳狀態。 */
    @GetMapping("/attendance/today")
    public AttendanceRecordDTO findTodayAttendance(@AuthenticationPrincipal Jwt jwt) {
        return attendanceService.findToday(driverId(jwt))
                .orElseThrow(() -> new IllegalArgumentException("尚未打上班卡"));
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

    /** 取得登入司機指定日期已發布的配送任務。 */
    @GetMapping("/tasks")
    public DriverTasksResponse findTasksByDate(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        return driverTasksService.findByDate(driverId(jwt), date);
    }

    /** 取得登入司機今天之後所有已發布任務，讓司機先確認日期與出發倉庫。 */
    @GetMapping("/tasks/upcoming")
    public List<DriverTasksResponse> findUpcomingTasks(@AuthenticationPrincipal Jwt jwt) {
        return driverTasksService.findUpcoming(driverId(jwt));
    }

    @PostMapping("/emergency-leave-requests")
    public EmergencyLeaveResponse requestEmergencyLeave(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody EmergencyLeaveRequestDTO request
    ) {
        return emergencyLeaveService.submit(driverId(jwt), request.getReason());
    }

    @GetMapping("/emergency-leave-requests")
    public List<EmergencyLeaveResponse> findMyEmergencyLeaves(@AuthenticationPrincipal Jwt jwt) {
        return emergencyLeaveService.findMine(driverId(jwt));
    }

    /** 倉庫點交：寫入實點箱數，相符轉為已點交，不符則建立異常與補送單。 */
    @PostMapping("/loading")
    public LoadingResponse loading(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody LoadingRequestDTO request) {
        return deliveryService.load(driverId(jwt), request);
    }

    /** 在司機班表中送出一般請假；時間不填代表整天，兩個時間都有則代表部分時段。 */
    @PostMapping("/leave-requests")
    public DriverLeaveResponse requestLeave(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody DriverLeaveRequestDTO request
    ) {
        return driverLeaveRequestService.submit(driverId(jwt), request);
    }

    /** 預排請假：同一假別可多選未來上班日，不同假別各自形成一組。 */
    @PostMapping("/leave-requests/planned-batches")
    public List<DriverLeaveBatchResponse> requestPlannedLeaveBatches(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody DriverPlannedLeaveBatchRequestDTO request
    ) {
        return driverLeaveRequestService.submitPlannedBatches(driverId(jwt), request);
    }

    /** 過去上班日可補整天或部分時段；已打卡時須填起訖時間。 */
    @PostMapping("/leave-requests/makeup")
    public DriverLeaveResponse requestMakeupLeave(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody DriverMakeupLeaveRequestDTO request
    ) {
        return driverLeaveRequestService.submitMakeupLeave(driverId(jwt), request);
    }

    @PostMapping("/leave-requests/makeup-batch")
    public List<DriverLeaveResponse> requestMakeupBatch(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody DriverMakeupLeaveBatchRequestDTO request) {
        return driverLeaveRequestService.submitMakeupBatch(driverId(jwt), request);
    }

    @GetMapping("/leave-requests/makeup-candidates")
    public List<LocalDate> findMakeupCandidates(@AuthenticationPrincipal Jwt jwt,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime leaveStart,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime leaveEnd) {
        return driverLeaveRequestService.findMakeupCandidates(driverId(jwt), from, to, leaveStart, leaveEnd);
    }

    /** 先上傳補請假佐證照片，再把回傳網址放入 makeup 請求；照片不是必填。 */
    @PostMapping(value = "/leave-requests/evidence-photo", consumes = "multipart/form-data")
    public PhotoUploadResponse uploadLeaveEvidencePhoto(
            @AuthenticationPrincipal Jwt jwt,
            @RequestPart("file") MultipartFile file
    ) {
        driverId(jwt);
        return new PhotoUploadResponse(leaveEvidencePhotoStorageService.store(file));
    }

    /** 查詢自己的請假與主管處理結果；month 可省略，unreadOnly=true 只抓未讀通知。 */
    @GetMapping("/leave-requests")
    public List<DriverLeaveResponse> findMyLeaves(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth month,
            @RequestParam(defaultValue = "false") boolean unreadOnly
    ) {
        return driverLeaveRequestService.findMine(driverId(jwt), month, unreadOnly);
    }

    @PostMapping("/leave-requests/{id}/read")
    public DriverLeaveResponse markLeaveResultRead(
            @AuthenticationPrincipal Jwt jwt,
            @org.springframework.web.bind.annotation.PathVariable Long id
    ) {
        return driverLeaveRequestService.markRead(driverId(jwt), id);
    }

    /** 司機只能查自己的請假單完整異動歷史。 */
    @GetMapping("/leave-requests/{id}/history")
    public List<DriverLeaveHistoryResponse> findMyLeaveHistory(
            @AuthenticationPrincipal Jwt jwt,
            @org.springframework.web.bind.annotation.PathVariable Long id
    ) {
        return driverLeaveRequestService.findMyHistory(driverId(jwt), id);
    }

    /** 記錄司機抵達門市的時間。 */
    @PostMapping("/arrive")
    public DeliveryRecordResponse arrive(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ArriveRequestDTO request) {
        return deliveryService.arrive(driverId(jwt), request);
    }

    /** 寫入交貨結果、箱數及備註。 */
    @PostMapping("/deliver")
    public DeliveryRecordResponse deliver(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody DeliverRequestDTO request) {
        return deliveryService.deliver(driverId(jwt), request);
    }

    /** 登記無人簽收並建立後續處理訂單。 */
    @PostMapping("/no-signature")
    public DeliveryRecordResponse noSignature(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody NoSignatureRequestDTO request) {
        return deliveryService.noSignature(driverId(jwt), request);
    }

    /** 上傳交貨或無人簽收照片；回傳網址再放入 deliver/no-signature 請求。 */
    @PostMapping(value = "/delivery-photo", consumes = "multipart/form-data")
    public PhotoUploadResponse uploadDeliveryPhoto(
            @AuthenticationPrincipal Jwt jwt,
            @RequestPart("file") MultipartFile file
    ) {
        driverId(jwt);
        return new PhotoUploadResponse(deliveryPhotoStorageService.store(file));
    }

    /** 司機回報配送途中發生的異常。 */
    @PostMapping("/exception")
    public ExceptionCaseResponse reportException(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody DriverExceptionRequestDTO request) {
        return driverExceptionService.report(driverId(jwt), request);
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

    /** 補傳出車時的里程表照片；保留原本的 JSON 登記 API。 */
    @PostMapping(value = "/mileage/start/photo", consumes = "multipart/form-data")
    public MileageLogResponse uploadStartMileagePhoto(
            @AuthenticationPrincipal Jwt jwt,
            @RequestPart("file") MultipartFile file
    ) {
        return mileageLogsService.attachStartPhoto(driverId(jwt), file);
    }

    /** 補傳收車時的里程表照片；保留原本的 JSON 登記 API。 */
    @PostMapping(value = "/mileage/end/photo", consumes = "multipart/form-data")
    public MileageLogResponse uploadEndMileagePhoto(
            @AuthenticationPrincipal Jwt jwt,
            @RequestPart("file") MultipartFile file
    ) {
        return mileageLogsService.attachEndPhoto(driverId(jwt), file);
    }

    /** GPS 點較晚送達或 OSRM 暫時失敗時，重新結算今天已收車的里程。 */
    @PostMapping("/mileage/recalculate")
    public MileageLogResponse recalculateMileage(@AuthenticationPrincipal Jwt jwt) {
        return mileageLogsService.recalculate(driverId(jwt));
    }

    /** 取得自己與調度中心的對話；afterId 可用於輪詢與斷線補抓。 */
    @GetMapping("/messages")
    public List<DriverMessageResponse> findMessages(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) Long afterId
    ) {
        return driverMessagesService.findMessages(driverId(jwt), afterId);
    }

    /** 司機發訊息給調度中心，對話身分一律從 JWT 判斷。 */
    @PostMapping("/messages")
    public DriverMessageResponse sendMessage(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody DriverMessageRequestDTO request
    ) {
        return driverMessagesService.sendFromDriver(driverId(jwt), request.getContent());
    }

    /** 將調度中心傳給目前司機的訊息標示為已讀。 */
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

    @PostMapping("/route")
    public GPSRouteResponse find(@Valid @RequestBody GPSRouteDTO dto) {
        return gpsRouteService.findRoute(dto);
    }

}
