package com.example.backend.service;

import com.example.backend.constants.DriverMessagePushType;
import com.example.backend.constants.ExceptionStatus;
import com.example.backend.constants.ExceptionType;
import com.example.backend.constants.MessageSender;
import com.example.backend.constants.RouteStatus;
import com.example.backend.dao.AdminUsersDAO;
import com.example.backend.dao.DriversDAO;
import com.example.backend.dao.ExceptionCasesDAO;
import com.example.backend.dao.OrdersDAO;
import com.example.backend.dao.RoutesDAO;
import com.example.backend.dao.StoresDAO;
import com.example.backend.dao.VehiclesDAO;
import com.example.backend.dto.request.DriverCaseRequestDTO;
import com.example.backend.dto.respones.AdminDriverCaseResponse;
import com.example.backend.dto.respones.DriverCaseOrdersResponse;
import com.example.backend.dto.respones.DriverCasePushEvent;
import com.example.backend.dto.respones.DriverCaseResponse;
import com.example.backend.dto.respones.DriverMessagePushResponse;
import com.example.backend.dto.respones.DriverMessageResponse;
import com.example.backend.entity.AdminUsersEntity;
import com.example.backend.entity.DriversEntity;
import com.example.backend.entity.ExceptionCasesEntity;
import com.example.backend.entity.OrdersEntity;
import com.example.backend.entity.RoutesEntity;
import com.example.backend.entity.StoresEntity;
import com.example.backend.entity.VehiclesEntity;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * 司機例外回報案件（司機端的「支援中心」）。
 *
 * <p>流程：司機建案 → 管理員在異常中心「接收」→ 雙方在聊天室溝通 → 管理員回異常中心填處理結果結案。
 * 案件存在 exception_cases（type = DRIVER_REPORT），對話存在 driver_messages（exception_case_id 指到案件）。
 * 訊息的存檔、已讀、推播沿用 DriverMessagesService；這裡只管案件層級的規則：
 * 是不是這位司機的、結案了沒、接收了沒。</p>
 *
 * <p>建案不改訂單或路線狀態：無人簽收、交貨短少破損、點交不符仍然走各自的按鈕。
 * 案件負責「先問調度中心怎麼辦」並留下紀錄；結案時主管可以把路線上送不完的單改期補送（見 close）。</p>
 */
@Service
@Transactional
public class DriverCaseService {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    /** 說明、處理結果的上限，跟 exception_cases 的 VARCHAR(1000) 一致 */
    private static final int MAX_TEXT_LENGTH = 1000;

    /**
     * 異常中心「進行中」的順序：還沒人接收的在前（鈴鐺列的也是這些），
     * 同一組裡不能繼續配送的在前，再來先回報的在前（等最久的先處理）。
     */
    private static final Comparator<ExceptionCasesEntity> ADMIN_OPEN_ORDER =
            Comparator.comparing((ExceptionCasesEntity item) -> item.getAcceptedAt() != null)
                    .thenComparing(item -> Boolean.TRUE.equals(item.getCanContinue()))
                    .thenComparing(ExceptionCasesEntity::getId);

    private final ExceptionCasesDAO exceptionCasesDAO;
    private final DriversDAO driversDAO;
    private final RoutesDAO routesDAO;
    private final OrdersDAO ordersDAO;
    private final StoresDAO storesDAO;
    private final VehiclesDAO vehiclesDAO;
    private final AdminUsersDAO adminUsersDAO;
    private final DriverMessagesService driverMessagesService;
    private final DeliveryService deliveryService;
    private final DeliveryExceptionService deliveryExceptionService;
    // 只負責「發事件」，真正推播由 DriverMessagesPushService 在交易 commit 後執行
    private final ApplicationEventPublisher eventPublisher;

    public DriverCaseService(
            ExceptionCasesDAO exceptionCasesDAO,
            DriversDAO driversDAO,
            RoutesDAO routesDAO,
            OrdersDAO ordersDAO,
            StoresDAO storesDAO,
            VehiclesDAO vehiclesDAO,
            AdminUsersDAO adminUsersDAO,
            DriverMessagesService driverMessagesService,
            DeliveryService deliveryService,
            DeliveryExceptionService deliveryExceptionService,
            ApplicationEventPublisher eventPublisher
    ) {
        this.exceptionCasesDAO = exceptionCasesDAO;
        this.driversDAO = driversDAO;
        this.routesDAO = routesDAO;
        this.ordersDAO = ordersDAO;
        this.storesDAO = storesDAO;
        this.vehiclesDAO = vehiclesDAO;
        this.adminUsersDAO = adminUsersDAO;
        this.driverMessagesService = driverMessagesService;
        this.deliveryService = deliveryService;
        this.deliveryExceptionService = deliveryExceptionService;
        this.eventPublisher = eventPublisher;
    }

    // ── 司機端 ──

    /**
     * 建立案件。司機、路線、建立時間都由後端決定，前端只送分類、說明、能不能繼續、照片、（可選的）訂單。
     * 沒排路線也能建：身體不適、App 壞掉這類狀況可能發生在上班前，路線就留 null。
     */
    public DriverCaseResponse create(Long driverId, DriverCaseRequestDTO request) {
        requireActiveDriver(driverId);
        if (request.getCategory() == null) {
            throw new IllegalArgumentException("請選擇回報的分類");
        }
        if (request.getCanContinue() == null) {
            throw new IllegalArgumentException("請選擇還能不能繼續配送");
        }
        String description = requireText(request.getDescription(), "請點選發生的狀況，或寫一段說明", "說明不能超過 1000 字");
        String photoUrl = normalizePhotoUrl(request.getPhotoUrl());
        RoutesEntity todayRoute = findTodayRoute(driverId);
        if (request.getOrderId() != null) {
            requireOrderOnRoute(request.getOrderId(), todayRoute);
        }

        ExceptionCasesEntity exceptionCase = new ExceptionCasesEntity();
        exceptionCase.setType(ExceptionType.DRIVER_REPORT);
        exceptionCase.setStatus(ExceptionStatus.OPEN);
        exceptionCase.setDriverId(driverId);
        exceptionCase.setRouteId(todayRoute == null ? null : todayRoute.getId());
        exceptionCase.setOrderId(request.getOrderId());
        exceptionCase.setCategory(request.getCategory());
        exceptionCase.setDescription(description);
        exceptionCase.setCanContinue(request.getCanContinue());
        exceptionCase.setPhotoUrl(photoUrl);
        exceptionCase = exceptionCasesDAO.save(exceptionCase);

        publishCaseEvent(DriverMessagePushType.CASE_OPENED, exceptionCase);
        return toDriverResponses(List.of(exceptionCase), Map.of()).getFirst();
    }

    /** 自己的案件：進行中的全部列，已結案的列最近 20 件；unreadCount 是調度中心的回覆裡還沒讀的。 */
    @Transactional(readOnly = true)
    public List<DriverCaseResponse> findForDriver(Long driverId) {
        List<ExceptionCasesEntity> cases = new ArrayList<>(exceptionCasesDAO.findByDriverIdAndTypeAndStatusOrderByIdDesc(
                driverId, ExceptionType.DRIVER_REPORT, ExceptionStatus.OPEN));
        cases.addAll(exceptionCasesDAO.findTop20ByDriverIdAndTypeAndStatusOrderByIdDesc(
                driverId, ExceptionType.DRIVER_REPORT, ExceptionStatus.CLOSED));
        Map<Long, Long> unread = driverMessagesService.countUnreadByCase(caseIds(cases), MessageSender.ADMIN);
        return toDriverResponses(cases, unread);
    }

    @Transactional(readOnly = true)
    public List<DriverMessageResponse> findMessagesForDriver(Long driverId, Long caseId, Long afterId) {
        findOwnCase(driverId, caseId, false);
        return driverMessagesService.findCaseMessages(driverId, caseId, afterId);
    }

    /** 司機在案件裡留言；還沒被接收也可以補充狀況，結案後就不能再留（前端拿掉輸入框只是畫面，這裡才是真的擋）。 */
    public DriverMessageResponse sendFromDriver(Long driverId, Long caseId, String content) {
        ExceptionCasesEntity exceptionCase = findOwnCase(driverId, caseId, true);
        requireOpen(exceptionCase, "這件案件已結案，不能再留言");
        return driverMessagesService.sendCaseMessage(driverId, caseId, MessageSender.DRIVER, null, content);
    }

    /** 司機讀的是調度中心的回覆，所以標 ADMIN 發的；結案後也能標（看紀錄）。 */
    public int markReadByDriver(Long driverId, Long caseId) {
        findOwnCase(driverId, caseId, false);
        return driverMessagesService.markCaseRead(driverId, caseId, MessageSender.ADMIN);
    }

    // ── 後台：異常中心管案件，聊天室管對話 ──

    /**
     * 異常中心的「司機回報」清單。status 不帶或 OPEN：進行中的全部，順序見 ADMIN_OPEN_ORDER；
     * CLOSED：最近結案的 50 件。unreadCount 是司機的訊息裡還沒被管理員讀的。
     * 舊版 API 建的回報（沒有司機、沒有分類）也會列出來，讓主管能把它們結案。
     */
    @Transactional(readOnly = true)
    public List<AdminDriverCaseResponse> findForAdmin(ExceptionStatus status) {
        List<ExceptionCasesEntity> cases;
        if (status == ExceptionStatus.CLOSED) {
            cases = exceptionCasesDAO.findTop50ByTypeAndStatusOrderByHandledAtDescIdDesc(
                    ExceptionType.DRIVER_REPORT, ExceptionStatus.CLOSED);
        } else {
            cases = new ArrayList<>(exceptionCasesDAO.findByTypeAndStatusOrderByIdAsc(
                    ExceptionType.DRIVER_REPORT, ExceptionStatus.OPEN));
            cases.sort(ADMIN_OPEN_ORDER);
        }
        Map<Long, Long> unread = driverMessagesService.countUnreadByCase(caseIds(cases), MessageSender.DRIVER);
        return toAdminResponses(cases, unread);
    }

    /**
     * 在異常中心接收案件，推 CASE_ACCEPTED：別的管理員的鈴鐺消掉，司機端從「等待回覆」變「處理中」。
     *
     * <p>用 SELECT ... FOR UPDATE 鎖住這一列：兩位管理員同時按，後按的要等前一位 commit，
     * 讀到的已經有接收人，就會收到「已由某某接收」，不會把前一位蓋掉。
     * 同一位管理員再按一次（例如連點兩下）直接回目前的狀態，不報錯也不重推。</p>
     */
    public AdminDriverCaseResponse accept(Long caseId, Long adminId) {
        ExceptionCasesEntity exceptionCase = findDriverCase(caseId, true);
        requireOpen(exceptionCase, "這件案件已經結案");
        if (exceptionCase.getAcceptedAt() != null) {
            if (adminId.equals(exceptionCase.getAcceptedAdminId())) {
                return toAdminResponse(exceptionCase);
            }
            throw new IllegalArgumentException("這件案件已由" + adminName(exceptionCase.getAcceptedAdminId()) + "接收");
        }
        exceptionCase.setAcceptedAdminId(adminId);
        exceptionCase.setAcceptedAt(LocalDateTime.now(TAIPEI));
        exceptionCasesDAO.save(exceptionCase);
        publishCaseEvent(DriverMessagePushType.CASE_ACCEPTED, exceptionCase);
        return toAdminResponse(exceptionCase);
    }

    /**
     * 結案視窗列給主管勾的：案件路線上還沒結束的單。
     * 「還沒結束」跟看板日期列用同一組狀態（DispatchDayService.UNFINISHED_STATUSES），
     * 這裡處理完的單，日期列就不會再算它未結案。
     */
    @Transactional(readOnly = true)
    public DriverCaseOrdersResponse findUnfinishedOrders(Long caseId) {
        ExceptionCasesEntity exceptionCase = findDriverCase(caseId, false);
        DriverCaseOrdersResponse response = new DriverCaseOrdersResponse();
        response.setOrders(List.of());
        if (exceptionCase.getRouteId() == null) {
            return response;
        }
        RoutesEntity route = routesDAO.findById(exceptionCase.getRouteId()).orElse(null);
        if (route == null) {
            return response;
        }
        List<OrdersEntity> orders = ordersDAO.findByRouteIdAndStatusInOrderBySequence(
                route.getId(), DispatchDayService.UNFINISHED_STATUSES);
        Map<Long, String> storeNames = storeNamesOf(orders);
        List<DriverCaseOrdersResponse.Item> items = new ArrayList<>();
        for (OrdersEntity order : orders) {
            DriverCaseOrdersResponse.Item item = new DriverCaseOrdersResponse.Item();
            item.setId(order.getId());
            item.setOrderNumber(order.getOrderNumber());
            item.setStoreName(storeNames.get(order.getStoreId()));
            item.setStatus(order.getStatus());
            items.add(item);
        }
        response.setRouteDate(route.getDate());
        response.setMustResolveAll(!items.isEmpty() && route.getDate().isBefore(LocalDate.now(TAIPEI)));
        response.setOrders(items);
        return response;
    }

    /**
     * 在異常中心填處理結果結案，推 CASE_CLOSED。還沒人接收也可以結（例如司機重複送出同一件），
     * 鈴鐺會跟著消。handledBy 跟一般異常一樣存管理員名稱。
     *
     * <p>redeliverOrderIds 是主管勾選要改期補送的單（見 redeliverUnfinishedOrders）。
     * 案件結了但單還停在點交或配送中，那天在看板日期列會一直是「未結案」，所以路線日期已過時一定要處理完。
     * 開出的補送單號接在處理結果後面，之後查案件才知道單去哪了。</p>
     */
    public AdminDriverCaseResponse close(Long caseId, String handledBy, String resolution,
                                         Collection<Long> redeliverOrderIds) {
        ExceptionCasesEntity exceptionCase = findDriverCase(caseId, true);
        requireOpen(exceptionCase, "這件案件已經結案");
        String text = requireText(resolution, "處理結果不能為空", "處理結果不能超過 1000 字");
        List<String> redelivered = redeliverUnfinishedOrders(
                exceptionCase, redeliverOrderIds == null ? Set.of() : new HashSet<>(redeliverOrderIds));
        if (!redelivered.isEmpty()) {
            text = text + "\n改期補送：" + String.join("、", redelivered);
            if (text.length() > MAX_TEXT_LENGTH) {
                // 丟例外整筆交易回滾，上面改的訂單、開的補送單都不會留下
                throw new IllegalArgumentException("處理結果加上補送單號超過 1000 字，請縮短處理結果");
            }
        }
        exceptionCase.setStatus(ExceptionStatus.CLOSED);
        exceptionCase.setHandledBy(handledBy);
        exceptionCase.setHandledAt(LocalDateTime.now(TAIPEI));
        exceptionCase.setResolution(text);
        exceptionCasesDAO.save(exceptionCase);
        publishCaseEvent(DriverMessagePushType.CASE_CLOSED, exceptionCase);
        return toAdminResponse(exceptionCase);
    }

    /**
     * 把勾選的單改期補送：原單 FAILED、開 DR- 補送單（DeliveryService.redeliverAfterDriverReport）。
     * 路線日期已過時，沒勾的單不能留，不然那天永遠結不了；當天的可以不勾，讓司機繼續送。
     *
     * <p>鎖的順序跟點交、撤回發布一樣「先路線、後訂單」：司機這時還在按點交或抵達，
     * 兩邊會排隊而不是互相卡死；鎖住之後才判斷狀態，司機剛送完的單就不會被改成 FAILED。</p>
     *
     * @return 每張補送單一行「原單號 → 補送單號（日期）」，寫進處理結果用
     */
    private List<String> redeliverUnfinishedOrders(ExceptionCasesEntity exceptionCase, Set<Long> orderIds) {
        Long routeId = exceptionCase.getRouteId();
        if (routeId == null) {
            if (!orderIds.isEmpty()) {
                throw new IllegalArgumentException("這件案件沒有路線，沒有訂單可以改期");
            }
            return List.of();
        }
        RoutesEntity route = routesDAO.findForUpdate(routeId)
                .orElseThrow(() -> new EntityNotFoundException("找不到路線，ID：" + routeId));
        Map<Long, OrdersEntity> unfinished = new LinkedHashMap<>();
        for (OrdersEntity order : ordersDAO.findByRouteIdForUpdate(routeId)) {
            if (DispatchDayService.UNFINISHED_STATUSES.contains(order.getStatus())) {
                unfinished.put(order.getId(), order);
            }
        }
        for (Long orderId : orderIds) {
            if (!unfinished.containsKey(orderId)) {
                throw new IllegalArgumentException("有訂單不在這條路線上或已經結束，請重新整理後再結案");
            }
        }
        LocalDate today = LocalDate.now(TAIPEI);
        int left = unfinished.size() - orderIds.size();
        if (left > 0 && route.getDate().isBefore(today)) {
            throw new IllegalArgumentException("路線日期已過，還有 " + left + " 張單沒結束，要全部改期補送才能結案");
        }

        LocalDateTime now = LocalDateTime.now(TAIPEI);
        List<String> redelivered = new ArrayList<>();
        for (OrdersEntity order : unfinished.values()) {
            if (!orderIds.contains(order.getId())) {
                continue;
            }
            LocalDate deliveryDate = deliveryExceptionService.nextDispatchDate(order, today);
            OrdersEntity followUpOrder = deliveryService.redeliverAfterDriverReport(order, deliveryDate, now);
            redelivered.add(order.getOrderNumber() + " → " + followUpOrder.getOrderNumber() + "（" + deliveryDate + "）");
        }
        return redelivered;
    }

    @Transactional(readOnly = true)
    public List<DriverMessageResponse> findMessagesForAdmin(Long caseId, Long afterId) {
        Long driverId = requireReporter(findDriverCase(caseId, false));
        return driverMessagesService.findCaseMessages(driverId, caseId, afterId);
    }

    /**
     * 管理員在案件裡回覆。要先在異常中心接收：「有管理員的訊息，就一定有接收人」永遠成立，
     * 接收時間只有一個地方會寫。接收之後哪一位管理員都能回，聊天室本來就是共用收件匣，接收人只代表負責人。
     */
    public DriverMessageResponse sendFromAdmin(Long caseId, Long adminId, String content) {
        ExceptionCasesEntity exceptionCase = findDriverCase(caseId, true);
        Long driverId = requireReporter(exceptionCase);
        requireOpen(exceptionCase, "這件案件已結案，不能再留言");
        if (exceptionCase.getAcceptedAt() == null) {
            throw new IllegalArgumentException("請先在異常中心接收這件案件，再回覆司機");
        }
        return driverMessagesService.sendCaseMessage(driverId, caseId, MessageSender.ADMIN, adminId, content);
    }

    /** 管理員讀的是司機發的訊息；已讀是所有管理員共用的。 */
    public int markReadByAdmin(Long caseId) {
        Long driverId = requireReporter(findDriverCase(caseId, false));
        return driverMessagesService.markCaseRead(driverId, caseId, MessageSender.DRIVER);
    }

    // ── 檢查 ──

    /**
     * 找司機回報的案件；其他類型的異常也當作找不到。
     * lock＝true 用 SELECT ... FOR UPDATE：接收、結案、留言會互相等，
     * 例如結案跟留言同時發生，留言要等結案 commit 後才判斷，已結案的案件就不會多出一則。
     */
    private ExceptionCasesEntity findDriverCase(Long caseId, boolean lock) {
        Optional<ExceptionCasesEntity> found = lock
                ? exceptionCasesDAO.findForUpdate(caseId)
                : exceptionCasesDAO.findById(caseId);
        return found.filter(item -> item.getType() == ExceptionType.DRIVER_REPORT)
                .orElseThrow(() -> new EntityNotFoundException("找不到案件，ID：" + caseId));
    }

    /** 別的司機的案件也回「找不到」：不讓人拿編號試出別人有沒有案件 */
    private ExceptionCasesEntity findOwnCase(Long driverId, Long caseId, boolean lock) {
        ExceptionCasesEntity exceptionCase = findDriverCase(caseId, lock);
        if (!driverId.equals(exceptionCase.getDriverId())) {
            throw new EntityNotFoundException("找不到案件，ID：" + caseId);
        }
        return exceptionCase;
    }

    private void requireOpen(ExceptionCasesEntity exceptionCase, String message) {
        if (exceptionCase.getStatus() != ExceptionStatus.OPEN) {
            throw new IllegalArgumentException(message);
        }
    }

    /** 舊版 API 建的回報沒有記司機，沒有對話可以看或回 */
    private Long requireReporter(ExceptionCasesEntity exceptionCase) {
        if (exceptionCase.getDriverId() == null) {
            throw new IllegalArgumentException("這件是舊版的回報，沒有記錄司機，無法對話");
        }
        return exceptionCase.getDriverId();
    }

    private void requireActiveDriver(Long driverId) {
        DriversEntity driver = driversDAO.findById(driverId)
                .orElseThrow(() -> new EntityNotFoundException("找不到司機，ID：" + driverId));
        if (!Boolean.TRUE.equals(driver.getIsActive())) {
            throw new IllegalArgumentException("司機帳號目前未啟用");
        }
    }

    /** 今天已發布給這位司機的路線；routes 有 (date, driver_id) 唯一鍵，一天最多一條，沒有是 null */
    private RoutesEntity findTodayRoute(Long driverId) {
        List<RoutesEntity> routes = routesDAO.findByDateAndDriverIdAndStatusOrderByIdAsc(
                LocalDate.now(TAIPEI), driverId, RouteStatus.PUBLISHED);
        return routes.isEmpty() ? null : routes.getFirst();
    }

    /** 帶訂單的話要是今天路線上的單：不然可以拿任何訂單編號建案，後台看到的門市、單號就是別人的 */
    private void requireOrderOnRoute(Long orderId, RoutesEntity todayRoute) {
        OrdersEntity order = ordersDAO.findById(orderId)
                .orElseThrow(() -> new EntityNotFoundException("找不到訂單，ID：" + orderId));
        if (todayRoute == null || !todayRoute.getId().equals(order.getRouteId())) {
            throw new IllegalArgumentException("只能回報今天路線上的訂單");
        }
    }

    /**
     * 照片只收交貨照片上傳 API 回傳的網址（/uploads/delivery-photos/檔名）。
     * 不擋的話可以塞任何外部網址，後台一打開案件，瀏覽器就會去載那個網址。
     */
    private String normalizePhotoUrl(String photoUrl) {
        if (photoUrl == null || photoUrl.isBlank()) {
            return null;
        }
        String url = photoUrl.strip();
        String prefix = DeliveryPhotoStorageService.PUBLIC_URL_PREFIX;
        String fileName = url.startsWith(prefix) ? url.substring(prefix.length()) : "";
        if (fileName.isEmpty() || fileName.contains("/") || fileName.contains("\\") || fileName.contains("..")) {
            throw new IllegalArgumentException("照片網址不正確，請重新上傳照片");
        }
        return url;
    }

    private String requireText(String value, String requiredMessage, String tooLongMessage) {
        String text = value == null ? "" : value.strip();
        if (text.isEmpty()) {
            throw new IllegalArgumentException(requiredMessage);
        }
        if (text.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException(tooLongMessage);
        }
        return text;
    }

    // ── 推播 ──

    /**
     * 推 CASE_*。後台那份有回報人、接收人，司機那份沒有（理由見 DriverCasePushEvent）；
     * 推播裡的 unreadCount 固定 0，收到的一方保留自己的未讀數。
     */
    private void publishCaseEvent(DriverMessagePushType type, ExceptionCasesEntity exceptionCase) {
        List<ExceptionCasesEntity> single = List.of(exceptionCase);
        DriverMessagePushResponse adminPush = DriverMessagePushResponse.ofCase(
                type, exceptionCase.getDriverId(), toAdminResponses(single, Map.of()).getFirst());
        DriverMessagePushResponse driverPush = exceptionCase.getDriverId() == null
                ? null
                : DriverMessagePushResponse.ofCase(
                        type, exceptionCase.getDriverId(), toDriverResponses(single, Map.of()).getFirst());
        eventPublisher.publishEvent(new DriverCasePushEvent(adminPush, driverPush));
    }

    // ── 組回應：名稱一次查齊，清單才不會每件案件各查一次（N+1）──

    private AdminDriverCaseResponse toAdminResponse(ExceptionCasesEntity exceptionCase) {
        Map<Long, Long> unread = driverMessagesService.countUnreadByCase(
                List.of(exceptionCase.getId()), MessageSender.DRIVER);
        return toAdminResponses(List.of(exceptionCase), unread).getFirst();
    }

    private List<DriverCaseResponse> toDriverResponses(List<ExceptionCasesEntity> cases, Map<Long, Long> unread) {
        Map<Long, OrdersEntity> orders = ordersOf(cases);
        Map<Long, String> storeNames = storeNamesOf(orders.values());
        List<DriverCaseResponse> responses = new ArrayList<>();
        for (ExceptionCasesEntity exceptionCase : cases) {
            DriverCaseResponse response = new DriverCaseResponse();
            fillCaseFields(response, exceptionCase, orders, storeNames, unread);
            responses.add(response);
        }
        return responses;
    }

    private List<AdminDriverCaseResponse> toAdminResponses(List<ExceptionCasesEntity> cases, Map<Long, Long> unread) {
        Map<Long, OrdersEntity> orders = ordersOf(cases);
        Map<Long, String> storeNames = storeNamesOf(orders.values());
        Map<Long, String> driverNames = driverNamesOf(cases);
        Map<Long, String> plateNumbers = plateNumbersByRoute(cases);
        Map<Long, String> adminNames = adminNamesOf(cases);
        List<AdminDriverCaseResponse> responses = new ArrayList<>();
        for (ExceptionCasesEntity exceptionCase : cases) {
            AdminDriverCaseResponse response = new AdminDriverCaseResponse();
            fillCaseFields(response, exceptionCase, orders, storeNames, unread);
            response.setDriverId(exceptionCase.getDriverId());
            response.setDriverName(driverNames.get(exceptionCase.getDriverId()));
            response.setRouteId(exceptionCase.getRouteId());
            response.setVehiclePlateNumber(plateNumbers.get(exceptionCase.getRouteId()));
            response.setAcceptedAdminId(exceptionCase.getAcceptedAdminId());
            response.setAcceptedAdminName(adminNames.get(exceptionCase.getAcceptedAdminId()));
            response.setHandledBy(exceptionCase.getHandledBy());
            responses.add(response);
        }
        return responses;
    }

    /** 司機端和後台共用的欄位。查名稱的 Map 都是 HashMap，拿 null 當 key 查不會丟例外 */
    private void fillCaseFields(DriverCaseResponse response, ExceptionCasesEntity exceptionCase,
                                Map<Long, OrdersEntity> orders, Map<Long, String> storeNames,
                                Map<Long, Long> unread) {
        OrdersEntity order = orders.get(exceptionCase.getOrderId());
        response.setId(exceptionCase.getId());
        response.setCategory(exceptionCase.getCategory());
        response.setStatus(exceptionCase.getStatus());
        response.setOrderId(exceptionCase.getOrderId());
        response.setOrderNumber(order == null ? null : order.getOrderNumber());
        response.setStoreName(order == null ? null : storeNames.get(order.getStoreId()));
        response.setDescription(exceptionCase.getDescription());
        response.setCanContinue(exceptionCase.getCanContinue());
        response.setPhotoUrl(exceptionCase.getPhotoUrl());
        response.setCreatedAt(exceptionCase.getCreatedAt());
        response.setAcceptedAt(exceptionCase.getAcceptedAt());
        response.setHandledAt(exceptionCase.getHandledAt());
        response.setResolution(exceptionCase.getResolution());
        response.setUnreadCount(unread.getOrDefault(exceptionCase.getId(), 0L));
    }

    private Map<Long, OrdersEntity> ordersOf(List<ExceptionCasesEntity> cases) {
        Map<Long, OrdersEntity> orders = new HashMap<>();
        for (OrdersEntity order : ordersDAO.findAllById(idsOf(cases, ExceptionCasesEntity::getOrderId))) {
            orders.put(order.getId(), order);
        }
        return orders;
    }

    private Map<Long, String> storeNamesOf(Collection<OrdersEntity> orders) {
        Map<Long, String> names = new HashMap<>();
        for (StoresEntity store : storesDAO.findAllById(idsOf(orders, OrdersEntity::getStoreId))) {
            names.put(store.getId(), store.getName());
        }
        return names;
    }

    private Map<Long, String> driverNamesOf(List<ExceptionCasesEntity> cases) {
        Map<Long, String> names = new HashMap<>();
        for (DriversEntity driver : driversDAO.findAllById(idsOf(cases, ExceptionCasesEntity::getDriverId))) {
            names.put(driver.getId(), driver.getName());
        }
        return names;
    }

    /** key 是路線 id：案件記的是路線，車牌要從路線排的車查 */
    private Map<Long, String> plateNumbersByRoute(List<ExceptionCasesEntity> cases) {
        List<RoutesEntity> routes = routesDAO.findAllById(idsOf(cases, ExceptionCasesEntity::getRouteId));
        Map<Long, String> platesByVehicle = new HashMap<>();
        for (VehiclesEntity vehicle : vehiclesDAO.findAllById(idsOf(routes, RoutesEntity::getVehicleId))) {
            platesByVehicle.put(vehicle.getId(), vehicle.getPlateNumber());
        }
        Map<Long, String> platesByRoute = new HashMap<>();
        for (RoutesEntity route : routes) {
            platesByRoute.put(route.getId(), platesByVehicle.get(route.getVehicleId()));
        }
        return platesByRoute;
    }

    private Map<Long, String> adminNamesOf(List<ExceptionCasesEntity> cases) {
        Map<Long, String> names = new HashMap<>();
        for (AdminUsersEntity admin : adminUsersDAO.findAllById(idsOf(cases, ExceptionCasesEntity::getAcceptedAdminId))) {
            names.put(admin.getId(), admin.getName());
        }
        return names;
    }

    private String adminName(Long adminId) {
        return adminUsersDAO.findById(adminId).map(AdminUsersEntity::getName).orElse("其他管理員");
    }

    private List<Long> caseIds(List<ExceptionCasesEntity> cases) {
        List<Long> ids = new ArrayList<>();
        for (ExceptionCasesEntity exceptionCase : cases) {
            ids.add(exceptionCase.getId());
        }
        return ids;
    }

    /** 收集不是 null 的 id；findAllById 拿到空集合會直接回空清單，不會查資料庫 */
    private static <T> Set<Long> idsOf(Collection<T> items, Function<T, Long> idOf) {
        Set<Long> ids = new HashSet<>();
        for (T item : items) {
            Long id = idOf.apply(item);
            if (id != null) {
                ids.add(id);
            }
        }
        return ids;
    }
}
