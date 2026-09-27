package com.example.backend.service;

import com.example.backend.constants.*;
import com.example.backend.dao.AttendanceRecordsDAO;
import com.example.backend.dao.MileageLogsDAO;
import com.example.backend.dao.OrdersDAO;
import com.example.backend.dao.RouteDeviationsDAO;
import com.example.backend.dao.RoutePlannedLegsDAO;
import com.example.backend.dao.RoutesDAO;
import com.example.backend.dto.respones.RouteDeviationPushResponse;
import com.example.backend.dto.respones.RouteDeviationResponse;
import com.example.backend.entity.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
// Jackson 3（Spring Boot 4 自動建立的 bean）；不是 com.fasterxml 的 Jackson 2，那個沒有 bean，注入會啟動失敗
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 偏離預定路線：把每筆 GPS 跟「目前這一段」的預定路線比對，確定偏離就記一筆、推播給後台。
 *
 * <p>分工：判斷規則在 {@link RouteDeviationRules}（純邏輯）、連續幾筆的狀態機在 {@link RouteDeviationTracker}；
 * 這裡負責查資料、呼叫規則、存 route_deviations、發推播事件。</p>
 *
 * <p>狀態放在兩個地方：</p>
 * <ul>
 *   <li>記憶體 trackers：每位司機一個 RouteDeviationTracker，記「連續第幾筆」。後端重啟會歸零，最多晚 30 秒發現偏離</li>
 *   <li>資料庫 route_deviations：確定的事件。endedAt 是 null 的那筆＝這位司機正在偏離</li>
 * </ul>
 * <p>所以為某位司機「建立」tracker 時（後端重啟後第一筆 GPS），要先查他有沒有進行中的偏離：
 * 有就用 {@code new RouteDeviationTracker(true)} 從偏離中開始。不這樣做的話，還在路線外的司機會被再開一筆（重複警報），
 * 重啟期間已經回到路線的那筆也永遠不會結束。</p>
 *
 * <p>「結束進行中的偏離」三個地方都會用到（回到路線、交貨、休息／收車／下班），建議抽成一個 private 方法：
 * findFirstByDriverIdAndEndedAtIsNullOrderByStartedAtDesc 找那一筆 → 填 endedAt、endReason → 存檔
 * → {@code eventPublisher.publishEvent(RouteDeviationPushResponse.ended(saved))} → {@code trackers.remove(driverId)}。
 * 最後一步不能漏：tracker 還停在「偏離中」的話，司機之後再開出去，狀態機只會等「回來」，永遠不會再發 STARTED。</p>
 */
@Service
public class RouteDeviationService {

    // 每位司機一個狀態機。GPS 上傳是多條執行緒同時進來（不同司機），每分鐘的排程又是另一條執行緒，
    // 所以用 ConcurrentHashMap；同一位司機 10 秒才傳一筆，不會兩筆同時改同一個 tracker
    private final Map<Long, RouteDeviationTracker> trackers = new ConcurrentHashMap<>();

    private final RoutesDAO routesDAO;
    private final OrdersDAO ordersDAO;
    private final RoutePlannedLegsDAO routePlannedLegsDAO;
    private final MileageLogsDAO mileageLogsDAO;
    private final AttendanceRecordsDAO attendanceRecordsDAO;
    private final RouteDeviationsDAO routeDeviationsDAO;
    // 解析 route_planned_legs.path："[[經度,緯度],…]" → double[][]
    private final JsonMapper jsonMapper;
    // 發 RouteDeviationPushResponse；交易 commit 後 RouteDeviationPushService 才真的推
    private final ApplicationEventPublisher eventPublisher;

    // 只能有一個建構子：有兩個又都沒標 @Autowired 時，Spring 不知道用哪個，會改找無參數建構子而啟動失敗。
    // 之後要多注入東西，就在這個建構子加參數，不要另外產生一個新的
    public RouteDeviationService(
            RoutesDAO routesDAO,
            OrdersDAO ordersDAO,
            RoutePlannedLegsDAO routePlannedLegsDAO,
            MileageLogsDAO mileageLogsDAO,
            AttendanceRecordsDAO attendanceRecordsDAO,
            RouteDeviationsDAO routeDeviationsDAO,
            JsonMapper jsonMapper,
            ApplicationEventPublisher eventPublisher
    ) {
        this.routesDAO = routesDAO;
        this.ordersDAO = ordersDAO;
        this.routePlannedLegsDAO = routePlannedLegsDAO;
        this.mileageLogsDAO = mileageLogsDAO;
        this.attendanceRecordsDAO = attendanceRecordsDAO;
        this.routeDeviationsDAO = routeDeviationsDAO;
        this.jsonMapper = jsonMapper;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 每筆 GPS 存好後呼叫（{@link RouteDeviationGpsListener}）。
     *
     * <p>為什麼是 REQUIRES_NEW，不能拿掉：呼叫這裡的時候，GPS 的交易已經 commit、但還沒完全收尾。
     * 用預設的 REQUIRED 會「加入」那個已經 commit 的交易，這裡存的偏離紀錄永遠不會被 commit，而且不會有任何錯誤訊息。
     * REQUIRES_NEW 另開一個全新的交易，存完自己 commit。</p>
     *
     * <p>步驟：</p>
     * <ol>
     *   <li>今天（timestamp 的日期）這位司機已發布的路線：
     *       routesDAO.findByDateAndDriverIdAndStatusOrderByIdAsc(日期, driverId, RouteStatus.PUBLISHED)；
     *       沒有就 return（沒排車的司機不檢查）</li>
     *   <li>要不要檢查：出車紀錄 mileageLogsDAO.findByDriverIdAndDate、出勤 attendanceRecordsDAO.findByDriverIdAndWorkDate 的 status
     *       → RouteDeviationRules.shouldCheck。不檢查時，有進行中的偏離就結束它
     *       （已收車 TRIP_ENDED、休息中 ON_BREAK、其他 OFF_DUTY），然後 return</li>
     *   <li>目前這一段：routePlannedLegsDAO.findAllByRouteIdOrderBySequenceAsc、ordersDAO.findByRouteIdOrderBySequence
     *       → RouteDeviationRules.currentLeg。回傳 null 時：有 IN_DELIVERY 的單（到門市交貨）就結束進行中的偏離，
     *       原因 DELIVERING、結束時間用這筆 GPS 的 timestamp（約等於到店時間）；沒有形狀就直接 return</li>
     *   <li>距離：jsonMapper.readValue(leg.getPath(), double[][].class) → RouteDeviationRules.distanceToPathMeters</li>
     *   <li>狀態機：trackers.computeIfAbsent(driverId, …) 建立時照類別說明決定起始狀態；呼叫 onDistance：
     *     <ul>
     *       <li>STARTED → 新增一筆（startedAt＝timestamp、startLat／startLng、startDistanceMeters、legSequence＝leg.getSequence()），
     *           {@code eventPublisher.publishEvent(RouteDeviationPushResponse.started(saved))}</li>
     *       <li>RESOLVED → 結束進行中那筆，原因 BACK_ON_ROUTE，結束時間＝timestamp</li>
     *       <li>NONE → 不做事</li>
     *     </ul>
     *   </li>
     * </ol>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onGpsPing(Long driverId, double lat, double lng, LocalDateTime timestamp) {
        LocalDate date = timestamp.toLocalDate();
        List<RoutesEntity> routes = routesDAO.findByDateAndDriverIdAndStatusOrderByIdAsc(date, driverId, RouteStatus.PUBLISHED);
        if (routes.isEmpty()) {
            return;
        }
        RoutesEntity route = routes.getFirst();
        MileageLogsEntity trip = mileageLogsDAO.findByDriverIdAndDate(driverId, date).orElse(null);
        AttendanceRecordsEntity attendance = attendanceRecordsDAO.findByDriverIdAndWorkDate(driverId, date).orElse(null);
        AttendanceStatus status = null;
        if (attendance != null) {
            status = attendance.getStatus();
        }
        if (!RouteDeviationRules.shouldCheck(trip, status)) {
            endOpenDeviation(driverId, notCheckingReason(trip, status), timestamp);
            return;
        }
        List<RoutePlannedLegsEntity> legs = routePlannedLegsDAO.findAllByRouteIdOrderBySequenceAsc(route.getId());
        List<OrdersEntity> orders = ordersDAO.findByRouteIdOrderBySequence(route.getId());
        RoutePlannedLegsEntity leg = RouteDeviationRules.currentLeg(legs, orders);
        if (leg == null) {
            for (OrdersEntity order : orders) {
                if (order.getStatus() == OrderStatus.IN_DELIVERY) {
                    endOpenDeviation(driverId, RouteDeviationEndReason.DELIVERING, timestamp);
                    break;
                }
            }
            return;
        }
        double[][] path = jsonMapper.readValue(leg.getPath(), double[][].class);
        double distanceMeters = RouteDeviationRules.distanceToPathMeters(lat, lng, path);

        RouteDeviationTracker tracker = trackers.get(driverId);
        if (tracker == null) {
            boolean hasOpen = routeDeviationsDAO
                    .findFirstByDriverIdAndEndedAtIsNullOrderByStartedAtDesc(driverId)
                    .isPresent();
            tracker = new RouteDeviationTracker(hasOpen);
            trackers.put(driverId, tracker);
        }
        RouteDeviationEvent event = tracker.onDistance(distanceMeters);
        if (event == RouteDeviationEvent.STARTED) {
            RouteDeviationsEntity deviation = new RouteDeviationsEntity();
            deviation.setRouteId(route.getId());
            deviation.setDriverId(driverId);
            deviation.setLegSequence(leg.getSequence());
            deviation.setStartedAt(timestamp);
            deviation.setStartLat(lat);
            deviation.setStartLng(lng);
            deviation.setStartDistanceMeters(distanceMeters);
            RouteDeviationsEntity saved = routeDeviationsDAO.save(deviation);
            eventPublisher.publishEvent(RouteDeviationPushResponse.started(saved));
        } else if (event == RouteDeviationEvent.RESOLVED) {
            endOpenDeviation(driverId, RouteDeviationEndReason.BACK_ON_ROUTE, timestamp);
            return;
        }

    }

    /**
     * 每分鐘由排程呼叫（{@link RouteDeviationEscalationScheduler}）：把每一筆進行中的偏離看一遍。
     *
     * <ol>
     *   <li>司機現在已經不該比對（休息、收車、下班）→ 結束這一筆，不升級。
     *       「開始休息就結束、休息不發警報」只能在這裡做：休息中的司機根本傳不了 GPS
     *       （AttendanceService.isGpsUploadAllowed 只收上班中、加班中），onGpsPing 不會被呼叫。
     *       結束時間用實際發生的時間（見 notCheckingSince），不是排程剛好跑到的 now</li>
     *   <li>前一天的偏離到現在還開著 → 結束（OFF_DUTY，那天 23:59:59）</li>
     *   <li>還沒升級、已經偏離滿 {@link RouteDeviationRules#ESCALATE_AFTER_MINUTES} 分鐘 → 升級成警報並推播。
     *       看的是時間不是 GPS，所以司機手機沒電、不再回傳時也升得上去</li>
     * </ol>
     *
     * <p>跟 onGpsPing 可能同時改到同一筆（例如這裡正要升級、那邊剛好判定回到路線）：
     * RouteDeviationsEntity 有 @Version，後存的一方會丟 ObjectOptimisticLockingFailureException，
     * 這一輪整批 rollback、推播也不會送出，下一分鐘再重來一次。</p>
     */
    @Transactional
    public void escalateOverdue(LocalDateTime now) {
        // startedAt 不晚於這個時間，就是已經偏離滿 10 分鐘
        LocalDateTime escalateIfStartedBy = now.minusMinutes(RouteDeviationRules.ESCALATE_AFTER_MINUTES);
        for (RouteDeviationsEntity deviation : routeDeviationsDAO.findAllByEndedAtIsNullOrderByStartedAtAsc()) {
            Long driverId = deviation.getDriverId();
            // 用偏離發生那天查出車與出勤，不用 now 的日期：跨日還沒結束的那一筆，要看的是那天的狀態
            LocalDate date = deviation.getStartedAt().toLocalDate();
            MileageLogsEntity trip = mileageLogsDAO.findByDriverIdAndDate(driverId, date).orElse(null);
            AttendanceRecordsEntity attendance = attendanceRecordsDAO.findByDriverIdAndWorkDate(driverId, date).orElse(null);
            AttendanceStatus status = null;
            if (attendance != null) {
                status = attendance.getStatus();
            }

            if (!RouteDeviationRules.shouldCheck(trip, status)) {
                RouteDeviationEndReason reason = notCheckingReason(trip, status);
                endDeviation(deviation, reason, notCheckingSince(reason, trip, attendance, deviation, now));
                continue;
            }
            if (date.isBefore(now.toLocalDate())) {
                // 前一天的偏離到現在還開著：通常是手機沒電、又忘了收車和打下班卡，出車與出勤都停在「進行中」。
                // 不結束的話，隔天這位司機一上路，tracker 會從資料庫還原成「偏離中」，當天真的偏離反而發不出警報
                endDeviation(deviation, RouteDeviationEndReason.OFF_DUTY, date.atTime(23, 59, 59));
                continue;
            }
            if (deviation.getEscalatedAt() == null && !deviation.getStartedAt().isAfter(escalateIfStartedBy)) {
                deviation.setEscalatedAt(now);
                RouteDeviationsEntity saved = routeDeviationsDAO.save(deviation);
                eventPublisher.publishEvent(RouteDeviationPushResponse.escalated(saved));
            }
        }
    }

    /**
     * 後台打開看板、WebSocket 重新連上時抓：還沒結束的偏離，先開始的在前。
     * 推播可能在斷線期間漏掉，所以畫面以這支的結果為準，推播只負責即時更新。
     */
    @Transactional(readOnly = true)
    public List<RouteDeviationResponse> findActive() {
        List<RouteDeviationResponse> active = new ArrayList<>();
        for (RouteDeviationsEntity deviation : routeDeviationsDAO.findAllByEndedAtIsNullOrderByStartedAtAsc()) {
            active.add(RouteDeviationResponse.from(deviation));
        }
        return active;
    }

    /**
     * 結束這位司機進行中的偏離（如果有的話），並且不管有沒有，都把 tracker 清掉。
     *
     * <p>tracker 一定要清：呼叫這裡代表已經停止比對（交貨、收車…），連續偏離／回來的計數不能帶到下一段。
     * 沒有進行中的偏離時也要清——例如偏離中連續計數還沒滿 3 筆就到店了，不清的話這 1～2 筆會留著，
     * 下一段一開始只要再偏一筆就達標發警報，等於門檻被打折。</p>
     */
    private void endOpenDeviation(Long driverId, RouteDeviationEndReason reason, LocalDateTime endedAt) {
        RouteDeviationsEntity open =
                routeDeviationsDAO.findFirstByDriverIdAndEndedAtIsNullOrderByStartedAtDesc(driverId).orElse(null);
        if (open != null) {
            endDeviation(open, reason, endedAt);
        }
        trackers.remove(driverId);
    }

    /**
     * 把這一筆偏離結束：填結束時間與原因、存檔、推播給後台，並清掉這位司機的 tracker（理由同 endOpenDeviation）。
     * escalateOverdue 手上已經有那一筆，直接呼叫這裡，不用再查一次。
     */
    private void endDeviation(RouteDeviationsEntity deviation, RouteDeviationEndReason reason, LocalDateTime endedAt) {
        deviation.setEndedAt(endedAt);
        deviation.setEndReason(reason);
        RouteDeviationsEntity saved = routeDeviationsDAO.save(deviation);
        eventPublisher.publishEvent(RouteDeviationPushResponse.ended(saved));
        trackers.remove(deviation.getDriverId());
    }

    /** 不比對的原因：已收車、休息中，其他（下班、還沒打卡）都算下班。onGpsPing 與 escalateOverdue 共用同一套 */
    private RouteDeviationEndReason notCheckingReason(MileageLogsEntity trip, AttendanceStatus status) {
        if (trip != null && trip.getEndTime() != null) {
            return RouteDeviationEndReason.TRIP_ENDED;
        }
        if (status == AttendanceStatus.ON_BREAK) {
            return RouteDeviationEndReason.ON_BREAK;
        }
        return RouteDeviationEndReason.OFF_DUTY;
    }

    /**
     * 停止比對實際是從什麼時候開始，拿來當偏離的結束時間：收車看出車紀錄的 endTime、休息看 breakStartedAt、下班看 clockOutAt。
     * 排程一分鐘才跑一次，用 now 會多算最多一分鐘，報表上的偏離時間就不準了。
     * 查不到就只好用 now；資料不一致、算出來比偏離開始還早時，用偏離開始的時間（結束不能早於開始）。
     */
    private LocalDateTime notCheckingSince(RouteDeviationEndReason reason, MileageLogsEntity trip,
                                           AttendanceRecordsEntity attendance, RouteDeviationsEntity deviation,
                                           LocalDateTime now) {
        LocalDateTime since = null;
        if (reason == RouteDeviationEndReason.TRIP_ENDED) {
            since = trip.getEndTime();
        } else if (attendance != null && reason == RouteDeviationEndReason.ON_BREAK) {
            since = attendance.getBreakStartedAt();
        } else if (attendance != null) {
            since = attendance.getClockOutAt();
        }
        if (since == null) {
            return now;
        }
        if (since.isBefore(deviation.getStartedAt())) {
            return deviation.getStartedAt();
        }
        return since;
    }

}
