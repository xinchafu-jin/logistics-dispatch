package com.example.backend.service;

import com.example.backend.constants.AiActionType;
import com.example.backend.constants.OrderStatus;
import com.example.backend.constants.ShiftType;
import com.example.backend.dto.request.*;
import com.example.backend.dto.respones.DispatchResponse;
import com.example.backend.dto.respones.DriverAvailabilityResponse;
import com.example.backend.dto.respones.PendingActionResponse;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class AiAssistantService {
    private final ChatClient chatClient;
    private final OrdersService ordersService;
    private final DriverScheduleService driverScheduleService;
    private final DispatchWorkflowService dispatchWorkflowService;
    private final DriversService driversService;
    private final WarehousesService warehousesService;

    public AiAssistantService(

            ChatClient.Builder chatClientBuilder,
            ChatMemory chatMemory,
            OrdersService ordersService,
            DriverScheduleService driverScheduleService,
            DispatchWorkflowService dispatchWorkflowService,
            DriversService driversService,
            WarehousesService warehousesService) {
        this.ordersService = ordersService;
        this.driverScheduleService = driverScheduleService;
        this.dispatchWorkflowService = dispatchWorkflowService;
        this.driversService = driversService;
        this.warehousesService = warehousesService;
        this.chatClient = chatClientBuilder
                .defaultSystem("你是物流調度系統的助理，用繁體中文，台灣圈用語簡潔回覆調度員關於班表、訂單、派車的查詢。" +
                        "▎ 待執行清單的內容一律以 listPendingActions 查詢結果為準,不可依照對話記憶推測。使用者要求加入動作時,一律呼叫對應工具,不要因為「記得加過」而跳過。")
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .defaultTools(this)
                .build();
    }

    // 同一份清單有兩個入口會同時改：對話中 AI 工具加入項目、調度員按刪除。
    // 外層 ConcurrentHashMap 管「誰的清單」，內層 CopyOnWriteArrayList 管清單本身（見 addToPlan），
    // 兩層都要執行緒安全，只有外層安全的話，同一份 list 被同時 add 和 removeIf 仍會出錯。
    private final Map<String, List<PendingActionResponse>> pendingAction = new ConcurrentHashMap<>();

    public String chat(String conversationId, String message) {
        return chatClient.prompt()
                .user(message)
                // 不加Lambda會每對話一次都new 一個advisors
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                // 工具需要知道是誰在講話，但這份資料不會送給模型
                .toolContext(Map.of("conversationId", conversationId))
                .call()
                .content();
    }

    // 讀取待執行清單；回傳複本，避免外部直接改到內部狀態
    public List<PendingActionResponse> getPlan(String conversationId) {
        List<PendingActionResponse> actions = pendingAction.get(conversationId);
        if (actions == null) {
            return new ArrayList<>();
        }
        return new ArrayList<>(actions);
    }

    // 調度員反悔時整批清
    public void clearPlan(String conversationId) {
        pendingAction.remove(conversationId);
    }

    /**
     * 刪除清單中的單一項目，回傳刪除後的整份清單，前端直接整包取代。
     *
     * <p>找不到 id 不報錯：最可能是已經確認、清除或在另一個分頁刪過了，
     * 前端要的只是最新清單。用項目 id 而不用位置刪，另一個分頁畫面過期時才不會刪到別筆。</p>
     */
    public List<PendingActionResponse> removeAction(String conversationId, String id) {
        List<PendingActionResponse> actions = pendingAction.get(conversationId);
        if (actions != null) {
            actions.removeIf(action -> action.getId().equals(id));
        }
        return getPlan(conversationId);
    }

    @Transactional
    public List<DispatchResponse> confirmPlan(String conversationId) {
        //取該conversationId 清單
        List<PendingActionResponse> actions = pendingAction.get(conversationId);
        if (actions == null || actions.isEmpty()) {
            throw new IllegalArgumentException("目前沒有待執行的動作");
        }
        //依 日期+ 倉庫分組
        List<PendingActionResponse> reassignActions = new ArrayList<>();
        Map<String, List<PendingActionResponse>> groups = new LinkedHashMap<>();
        Set<LocalDate> publishDates = new LinkedHashSet<>();
        for (PendingActionResponse action : actions) {
            if (action.getType() == AiActionType.PUBLISH_DAY) {
                publishDates.add(action.getDate());
            } else {
                reassignActions.add(action);
            }
        }

        for (PendingActionResponse action : reassignActions) {
            String key = action.getDate() + "#" + action.getWarehouseId();
            List<PendingActionResponse> group = groups.get(key);
            if (group == null) {
                group = new ArrayList<>();
                groups.put(key, group);
            }
            group.add(action);
        }

        List<DispatchResponse> results = new ArrayList<>();
        //每組撈看板 → 疊動作 → 一次 reassign
        for (List<PendingActionResponse> group : groups.values()) {
            LocalDate date = group.getFirst().getDate();
            Long warehouseId = group.getFirst().getWarehouseId();

            DispatchResponse board = dispatchWorkflowService.getBoard(date, warehouseId);
            ReassignDTO dto = toReassignDTO(board);
            for (PendingActionResponse action : group) {
                apply(dto, action);
            }
            results.add(dispatchWorkflowService.reassign(dto));
        }
        for (LocalDate date : publishDates) {
            results.addAll(dispatchWorkflowService.publish(date));
        }
        pendingAction.remove(conversationId);
        return results;
    }

    private OrdersDTO findConfirmedOrderByNumber(LocalDate date, Long warehouseId, String orderNumber, String warehouseName) {
        for (OrdersDTO order : ordersService.findByDeliveryDateAndWarehouseId(date, warehouseId)) {
            if (order.getOrderNumber().equals(orderNumber)) {
                if (order.getStatus() != (OrderStatus.CONFIRMED)) {
                    throw new IllegalArgumentException("訂單 " + orderNumber + " 目前狀態為 " + order.getStatus() + "，只有已確認的訂單可以移動");
                }
                return order;
            }
        }
        throw new IllegalArgumentException(date + " " + warehouseName + " 找不到訂單 " + orderNumber);
    }

    private ReassignDTO toReassignDTO(DispatchResponse board) {
        ReassignDTO dto = new ReassignDTO();
        dto.setDate(board.getDate());
        dto.setWarehouseId(board.getWarehouse().getId());

        List<ReassignDTO.RouteAssignment> routes = new ArrayList<>();
        for (DispatchResponse.RouteResponse route : board.getRoutes()) {

            ReassignDTO.RouteAssignment assignment = new ReassignDTO.RouteAssignment();
            assignment.setVehicleId(route.getVehicleId());
            assignment.setDriverId(route.getDriverId());
            List<Long> orderId = new ArrayList<>();
            for (DispatchResponse.StopResponse stop : route.getStops()) {
                orderId.add(stop.getOrderId());
            }
            assignment.setOrderIds(orderId);
            routes.add(assignment);
        }
        dto.setRoutes(routes);
        return dto;
    }

    private void apply(ReassignDTO dto, PendingActionResponse action) {
        if (action.getType() == AiActionType.ASSIGN_DRIVER) {
            applyAssignDriver(dto, action);
            return;
        }
        if (action.getType() == AiActionType.MOVE_ORDER) {
            applyMoveOrder(dto, action);
            return;
        }
        throw new IllegalArgumentException("尚未支援的動作類型：" + action.getType());
    }

    //  交換訂單主方法
    String addMoveOrderAction(String conversationId, String date, String orderNumber, String targetPlateNumber, String warehouseName) {
        LocalDate deliveryDate = LocalDate.parse(date);
        Long warehouseId = findWarehouseIdByName(warehouseName);
        DispatchResponse board = dispatchWorkflowService.getBoard(deliveryDate, warehouseId);
        Long targetVehicleId = findVehicleIdByPlate(board, targetPlateNumber);
        OrdersDTO orders = findConfirmedOrderByNumber(deliveryDate, warehouseId, orderNumber, warehouseName);
        String currentPlate = findCurrentPlate(board, orders.getId());
        if (targetPlateNumber.equals(currentPlate)) {
            throw new IllegalArgumentException("訂單 " + orderNumber + " 已經在 " + targetPlateNumber + " 上，不需要移動");
        }
        PendingActionResponse pendingActionResponse = new PendingActionResponse();
        pendingActionResponse.setType(AiActionType.MOVE_ORDER);
        pendingActionResponse.setDate(deliveryDate);
        pendingActionResponse.setWarehouseId(warehouseId);
        // findWarehouseIdByName 是 equals 完全比對，能走到這裡代表這個字串與資料庫名稱一字不差
        pendingActionResponse.setWarehouseName(warehouseName);
        pendingActionResponse.setOrderId(orders.getId());
        pendingActionResponse.setVehicleId(targetVehicleId);
        String from;
        if (currentPlate == null) {
            from = "未排入";
        } else {
            from = currentPlate;
        }
        pendingActionResponse.setSummary("將訂單 " + orderNumber + " 從 " + from + " 移到 " + targetPlateNumber + "（排在最後一站）");
        List<PendingActionResponse> actions = addToPlan(conversationId, pendingActionResponse);
        return "已加入待執行清單：" + pendingActionResponse.getSummary()
                + "。目前清單共 " + actions.size() + " 項，尚未執行，請調度員在畫面上確認後才會生效。";
    }

    private void applyAssignDriver(ReassignDTO dto, PendingActionResponse action) {
        for (ReassignDTO.RouteAssignment assignment : dto.getRoutes()) {
            if (assignment.getVehicleId().equals(action.getVehicleId())) {
                assignment.setDriverId(action.getDriverId());
                return;
            }
        }
        // 加入清單時驗證過，但調度員確認前別人可能重排過，所以這裡會真的發生
        throw new IllegalArgumentException(action.getSummary()
                + "：該車輛當天已無排線，可能在你確認前被重新排過，請重新確認");
    }

    private void applyMoveOrder(ReassignDTO dto, PendingActionResponse action) {
        ReassignDTO.RouteAssignment target = null;
        for (ReassignDTO.RouteAssignment assignment : dto.getRoutes()) {
            if (assignment.getVehicleId().equals(action.getVehicleId())) {
                target = assignment;
            }
            // 不管原本排在哪台車上都先拔掉；訂單本來未排入的話這行不會有任何效果。
            // 傳進去的是 Long 不是 int，走的是 remove(Object) 而不是 remove(index)
            assignment.getOrderIds().remove(action.getOrderId());
        }
        // 加入清單時驗證過，但調度員確認前別人可能重排過，所以這裡會真的發生
        if (target == null) {
            throw new IllegalArgumentException(action.getSummary()
                    + "：目標車輛當天已無排線，可能在你確認前被重新排過，請重新確認");
        }
        target.getOrderIds().add(action.getOrderId());
        // 搬空的路線不能留著：reassign 會建出沒有訂單的空路線
        Iterator<ReassignDTO.RouteAssignment> it = dto.getRoutes().iterator();
        while (it.hasNext()) {
            ReassignDTO.RouteAssignment assignment = it.next();
            if (!assignment.getOrderIds().isEmpty()) {
                continue;
            }
            // 有司機就擋下，直接刪會讓這位司機的指派默默消失
            if (assignment.getDriverId() != null) {
                throw new IllegalArgumentException(action.getSummary()
                        + "：原路線搬空後仍指派著司機，請先處理該路線的司機再移動");
            }
            it.remove();
        }
    }


    @Tool(description = "查詢倉庫清單，取得倉庫名稱與對應的 ID")
    List<WarehousesDTO> listWarehouses() {
        return warehousesService.findAll();
    }

    @Tool(description = "查詢在職司機清單，取得司機姓名與對應的 ID")
    List<DriversDTO> listDrivers() {
        return driversService.findAll();
    }

    @Tool(description = "依年月查詢該月司機班表主檔，回傳班表 ID 供後續查詢班次使用")
    ScheduleMonthDTO findScheduleMonth(@ToolParam(description = "年月，格式 yyyy-MM，例如 2026-09") String yearMonth) {
        return driverScheduleService.findMonth(YearMonth.parse(yearMonth));
    }

    @Tool(description = "依班表 ID 查詢該月所有司機每天的班次")
    List<DriverShiftDTO> findMonthShifts(@ToolParam(description = "班表主檔 ID，從 findScheduleMonth 取得") Long scheduleMonthId) {
        return driverScheduleService.findMonthShifts(scheduleMonthId);
    }

    @Tool(description = "查詢某一天可以派車的司機。可派的條件：當天班表是上班、還沒被派到任何倉庫的路線、"
            + "也不在待執行清單裡。回傳可派與不可派兩份清單，不可派的附原因。指派司機前必須先呼叫此工具")
    DriverAvailabilityResponse findAvailableDrivers(
            @ToolParam(description = "配送日期，格式 yyyy-MM-dd") String date,
            ToolContext toolContext) {
        String conversationId = (String) toolContext.getContext().get("conversationId");
        LocalDate workDate = LocalDate.parse(date);

        // 當月還沒產生班表時 findMonth 會丟例外，訊息「找不到 yyyy-MM 的班表」直接回給 AI
        ScheduleMonthDTO month = driverScheduleService.findMonth(YearMonth.from(workDate));
        Map<Long, DriverShiftDTO> shifts = findShiftsOn(month.getId(), workDate);
        Map<Long, String> taken = findTakenDrivers(workDate);
        Map<Long, String> pending = findPendingDrivers(conversationId, workDate);

        DriverAvailabilityResponse response = new DriverAvailabilityResponse();
        response.setDate(workDate);
        response.setScheduleStatus(month.getStatus());

        for (DriversDTO driver : driversService.findAll()) {
            // 停用的司機直接不列：列進不可派清單，AI 只會多解釋一段調度員不在意的事
            if (!Boolean.TRUE.equals(driver.getIsActive())) {
                continue;
            }
            DriverShiftDTO shift = shifts.get(driver.getId());
            String reason = unavailableReason(shift, taken.get(driver.getId()), pending.get(driver.getId()));

            DriverAvailabilityResponse.DriverItem item = new DriverAvailabilityResponse.DriverItem();
            item.setDriverId(driver.getId());
            item.setAccount(driver.getAccount());
            item.setName(driver.getName());
            if (reason == null) {
                item.setWorkStart(shift.getWorkStart());
                item.setWorkEnd(shift.getWorkEnd());
                response.getAvailable().add(item);
            } else {
                item.setReason(reason);
                response.getUnavailable().add(item);
            }
        }
        return response;
    }

    @Tool(description = "依日期與倉庫查詢當天訂單清單，含指派狀態")
    List<OrdersDTO> findOrders(
            @ToolParam(description = "配送日期，格式 yyyy-MM-dd") String deliveryDate,
            @ToolParam(description = "倉庫 ID，從 listWarehouses 取得") Long warehouseId) {
        return ordersService.findByDeliveryDateAndWarehouseId(LocalDate.parse(deliveryDate), warehouseId);
    }

    @Tool(description = "依日期與倉庫查詢當天派車看板，含每條路線的司機、車輛、訂單，以及尚未排入路線的訂單")
    DispatchResponse getDispatchBoard(
            @ToolParam(description = "配送日期，格式 yyyy-MM-dd") String date,
            @ToolParam(description = "倉庫 ID，從 listWarehouses 取得") Long warehouseId) {
        return dispatchWorkflowService.getBoard(LocalDate.parse(date), warehouseId);
    }

    @Tool(description = "指派司機給某天某條路線。此動作不會立即執行，只會加入待執行清單")
    String proposeAssignDriver(
            @ToolParam(description = "配送日期，格式 yyyy-MM-dd") String date,
            @ToolParam(description = "倉庫名稱") String warehouseName,
            @ToolParam(description = "車牌號碼") String plateNumber,
            @ToolParam(description = "司機帳號，先用 findAvailableDrivers 確認當天可派") String driverAccount,
            ToolContext toolContext) {
        String conversationId = (String) toolContext.getContext().get("conversationId");
        return addAssignDriverAction(conversationId, date, warehouseName, plateNumber, driverAccount);
    }

    @Tool(description = "查詢目前的待執行清單內容。回答清單有哪些動作時必須呼叫此工具，不可依對話記憶回答")
    List<PendingActionResponse> listPendingActions(ToolContext toolContext) {
        String conversationId = (String) toolContext.getContext().get("conversationId");
        return getPlan(conversationId);
    }

    @Tool(description = "將某張訂單移到同一天同倉庫的另一台車，會排在該車最後一站。"
            + "要讓某張訂單改由某位司機配送時，也使用此工具，移到該司機負責的車輛。"
            + "此動作不會立即執行，只會加入待執行清單，等調度員確認後才生效")
    String proposeMoveOrder(
            @ToolParam(description = "配送日期，格式 yyyy-MM-dd") String date,
            @ToolParam(description = "倉庫名稱，必須是 listWarehouses 回傳的完整名稱") String warehouseName,
            @ToolParam(description = "訂單編號，例如 DO-TEST-203，從 findOrders 或 getDispatchBoard 取得") String orderNumber,
            @ToolParam(description = "要移過去的目標車牌號碼") String targetPlateNumber,
            ToolContext toolContext
    ) {
        String conversationId = (String) toolContext.getContext().get("conversationId");
        return addMoveOrderAction(conversationId, date, orderNumber, targetPlateNumber, warehouseName);
    }

    @Tool(description = "發布某一天的排班，讓司機在手機上看得到任務。發布範圍是當天全部倉庫，"
            + "不能只發布單一倉庫。此動作不會立即執行，只會加入待執行清單")
    String proposePublish(
            @ToolParam(description = "配送日期，格式 yyyy-MM-dd") String date,
            ToolContext toolContext
    ) {
        String conversationId = (String) toolContext.getContext().get("conversationId");
        return addPublishAction(conversationId, date);
    }

    private String addPublishAction(String conversationId, String date) {

        LocalDate deliveryDate = LocalDate.parse(date);
        PendingActionResponse pendingActionResponse = new PendingActionResponse();
        pendingActionResponse.setType(AiActionType.PUBLISH_DAY);
        pendingActionResponse.setDate(deliveryDate);
        pendingActionResponse.setSummary("發布 " + date + " 全部倉庫的排班");

        List<PendingActionResponse> plan = addToPlan(conversationId, pendingActionResponse);
        return "已加入待執行清單：" + pendingActionResponse.getSummary()
                + "。目前清單共 " + plan.size() + " 項，尚未執行，在畫面上確認後才會生效。";
    }

    private String addAssignDriverAction(String conversationId, String date, String warehouseName, String plateNumber, String driverAccount) {
        LocalDate deliveryDate = LocalDate.parse(date);
        Long warehouseId = findWarehouseIdByName(warehouseName);
        DispatchResponse board = dispatchWorkflowService.getBoard(deliveryDate, warehouseId);
        Long vehicleId = findVehicleIdByPlate(board, plateNumber);
        DriversDTO driver = findActiveDriverByAccount(driverAccount);

        PendingActionResponse action = new PendingActionResponse();
        action.setType(AiActionType.ASSIGN_DRIVER);
        action.setDate(deliveryDate);
        action.setWarehouseId(warehouseId);
        // 同 addMoveOrderAction：名稱已與資料庫完全比對過
        action.setWarehouseName(warehouseName);
        action.setVehicleId(vehicleId);
        action.setDriverId(driver.getId());
        // 姓名與帳號都取自資料庫，不用 LLM 傳進來的字串，確認視窗看到的必定是真實資料
        action.setSummary("指派司機 " + driver.getName() + "（" + driver.getAccount() + "）給 " + plateNumber);

        List<PendingActionResponse> actions = addToPlan(conversationId, action);
        return "已加入待執行清單：" + action.getSummary() + "（" + date + " " + warehouseName
                + "）。目前清單共 " + actions.size() + " 項，尚未執行，在畫面上確認後才會生效。";
    }

    private Long findWarehouseIdByName(String name) {
        for (WarehousesDTO warehouses : warehousesService.findAll()) {
            if (warehouses.getName().equals(name)) {
                return warehouses.getId();
            }
        }
        throw new IllegalArgumentException("找不到倉庫「" + name + "」");
    }

    private Long findVehicleIdByPlate(DispatchResponse board, String plateNumber) {
        for (DispatchResponse.RouteResponse route : board.getRoutes()) {
            if (plateNumber.equals(route.getPlateNumber())) {
                return route.getVehicleId();
            }
        }
        throw new IllegalArgumentException(("當天沒有車牌 " + plateNumber + " 的排線，無法指派司機"));

    }

    private DriversDTO findActiveDriverByAccount(String account) {
        DriversDTO driver = driversService.findByAccount(account);
        // reassign 也會擋非在職司機，這裡重複檢查是為了讓調度員在加入清單當下就知道，
        // 而不是排完好幾項按確認才被整批打回
        if (!Boolean.TRUE.equals(driver.getIsActive())) {
            throw new IllegalArgumentException("司機「" + driver.getName() + "」目前非在職狀態，無法指派");
        }
        return driver;
    }

    private List<PendingActionResponse> addToPlan(String conversationId, PendingActionResponse action) {
        // id 在這裡統一產生：三種動作都經過這裡，之後加新動作也不會漏設。
        // 用 UUID 不用遞增數字：後端重啟計數器歸零，沒重整的舊畫面送來的編號會對到另一筆新項目
        action.setId(UUID.randomUUID().toString());
        // computeIfAbsent 取代原本的 get 再 put：兩個請求同時第一次加入時，不會各自建一份 list 互相覆蓋
        List<PendingActionResponse> actions =
                pendingAction.computeIfAbsent(conversationId, key -> new CopyOnWriteArrayList<>());
        actions.add(action);
        return actions;
    }

    private String findCurrentPlate(DispatchResponse board, Long orderId) {
        for (DispatchResponse.RouteResponse route : board.getRoutes()) {
            for (DispatchResponse.StopResponse stop : route.getStops()) {
                if (orderId.equals(stop.getOrderId())) {
                    return route.getPlateNumber();
                }
            }
        }
        return null;
    }

    /** 指定那天每位司機的班次，以 driverId 查。班表只能整月撈，這裡只留那一天 */
    private Map<Long, DriverShiftDTO> findShiftsOn(Long scheduleMonthId, LocalDate workDate) {
        Map<Long, DriverShiftDTO> shifts = new HashMap<>();
        for (DriverShiftDTO shift : driverScheduleService.findMonthShifts(scheduleMonthId)) {
            if (workDate.equals(shift.getWorkDate())) {
                shifts.put(shift.getDriverId(), shift);
            }
        }
        return shifts;
    }

    /**
     * 當天已經在路線上的司機，值是「車牌（倉庫）」。
     *
     * <p>司機不綁倉庫、一天只開一條線，所以要掃全部倉庫，只看一個倉會漏。
     * 草稿路線上的司機也算：reassign 同樣會擋一天兩條線。
     * 同倉互換司機時對方也會顯示已被派，這是提示，proposeAssignDriver 不會因此擋下。</p>
     */
    private Map<Long, String> findTakenDrivers(LocalDate date) {
        Map<Long, String> taken = new HashMap<>();
        for (WarehousesDTO warehouse : warehousesService.findAll()) {
            DispatchResponse board = dispatchWorkflowService.getBoard(date, warehouse.getId());
            for (DispatchResponse.RouteResponse route : board.getRoutes()) {
                if (route.getDriverId() != null) {
                    taken.put(route.getDriverId(), route.getPlateNumber() + "（" + warehouse.getName() + "）");
                }
            }
        }
        return taken;
    }

    /**
     * 待執行清單裡已經指派出去、還沒確認的司機，值是那筆動作的摘要。
     *
     * <p>這些指派資料庫還查不到。不另外標出來的話，AI 可能在同一份清單裡把同一個人派兩次，
     * 要到按確認時才被 reassign 擋下，整批回滾。</p>
     */
    private Map<Long, String> findPendingDrivers(String conversationId, LocalDate date) {
        Map<Long, String> pending = new HashMap<>();
        for (PendingActionResponse action : getPlan(conversationId)) {
            if (action.getType() == AiActionType.ASSIGN_DRIVER && date.equals(action.getDate())) {
                pending.put(action.getDriverId(), action.getSummary());
            }
        }
        return pending;
    }

    /**
     * 不可派的原因；可派回傳 null。
     *
     * <p>班表優先：沒上班的人就算被派了也不能出車。
     * 但「沒上班卻被派」要特別講出來 —— reassign 不檢查班表，這種資料確實可能存在，
     * 只回「休假」的話，那條路線其實缺人的問題會被藏起來。</p>
     */
    private String unavailableReason(DriverShiftDTO shift, String taken, String pending) {
        String shiftReason = shiftReason(shift);
        if (shiftReason != null) {
            if (taken != null) {
                return shiftReason + "，但目前仍被派在 " + taken + "，那條路線需要改派";
            }
            return shiftReason;
        }
        if (taken != null) {
            return "已被派在 " + taken;
        }
        if (pending != null) {
            return "已在待執行清單中：" + pending;
        }
        return null;
    }

    /** 班次不是上班時的原因；上班回傳 null */
    private String shiftReason(DriverShiftDTO shift) {
        if (shift == null) {
            // 司機在當月班表產生之後才建立，還沒同步進班表
            return "當月班表沒有這位司機的班次";
        }
        if (shift.getShiftType() == ShiftType.WORK) {
            return null;
        }
        if (shift.getShiftType() == ShiftType.DAY_OFF) {
            return "休假";
        }
        if (shift.getShiftType() == ShiftType.LEAVE) {
            if (shift.getChangeReason() == null) {
                return "請假";
            }
            return "請假（" + shift.getChangeReason() + "）";
        }
        return "未排班";
    }

}
