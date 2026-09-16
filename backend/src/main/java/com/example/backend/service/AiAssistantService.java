package com.example.backend.service;

import com.example.backend.constants.AiActionType;
import com.example.backend.constants.OrderStatus;
import com.example.backend.dto.request.*;
import com.example.backend.dto.respones.DispatchResponse;
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

@Service
public class AiAssistantService {
    private final ChatClient chatClient;
    private final OrdersService ordersService;
    private final DriverScheduleService driverScheduleService;
    private final DispatchService dispatchService;
    private final DriversService driversService;
    private final WarehousesService warehousesService;

    public AiAssistantService(

            ChatClient.Builder chatClientBuilder,
            ChatMemory chatMemory,
            OrdersService ordersService,
            DriverScheduleService driverScheduleService,
            DispatchService dispatchService,
            DriversService driversService,
            WarehousesService warehousesService) {
        this.ordersService = ordersService;
        this.driverScheduleService = driverScheduleService;
        this.dispatchService = dispatchService;
        this.driversService = driversService;
        this.warehousesService = warehousesService;
        this.chatClient = chatClientBuilder
                .defaultSystem("你是物流調度系統的助理，用繁體中文，台灣圈用語簡潔回覆調度員關於班表、訂單、派車的查詢。" +
                        "▎ 待執行清單的內容一律以 listPendingActions 查詢結果為準,不可依照對話記憶推測。使用者要求加入動作時,一律呼叫對應工具,不要因為「記得加過」而跳過。")
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .defaultTools(this)
                .build();
    }

    //ConcurrentHashMap 為了可以多執行緒使用 實際上我也不知道會不會有多個帳號同時使用 寫起來放 不用也可以
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

            DispatchResponse board = dispatchService.getBoard(date, warehouseId);
            ReassignDTO dto = toReassignDTO(board);
            for (PendingActionResponse action : group) {
                apply(dto, action);
            }
            results.add(dispatchService.reassign(dto));
        }
        for (LocalDate date : publishDates) {
            results.addAll(dispatchService.publish(date));
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
        DispatchResponse board = dispatchService.getBoard(deliveryDate, warehouseId);
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
        return dispatchService.getBoard(LocalDate.parse(date), warehouseId);
    }

    @Tool(description = "指派司機給某天某條路線。此動作不會立即執行，只會加入待執行清單")
    String proposeAssignDriver(
            @ToolParam(description = "配送日期，格式 yyyy-MM-dd") String date,
            @ToolParam(description = "倉庫名稱") String warehouseName,
            @ToolParam(description = "車牌號碼") String plateNumber,
            @ToolParam(description = "司機帳號，必須從 listDrivers 取得") String driverAccount,
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
        DispatchResponse board = dispatchService.getBoard(deliveryDate, warehouseId);
        Long vehicleId = findVehicleIdByPlate(board, plateNumber);
        DriversDTO driver = findActiveDriverByAccount(driverAccount);

        PendingActionResponse action = new PendingActionResponse();
        action.setType(AiActionType.ASSIGN_DRIVER);
        action.setDate(deliveryDate);
        action.setWarehouseId(warehouseId);
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
        List<PendingActionResponse> actions = pendingAction.get(conversationId);
        if (actions == null) {
            actions = new ArrayList<>();
            pendingAction.put(conversationId, actions);
        }
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

}
