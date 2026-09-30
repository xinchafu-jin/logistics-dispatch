package com.example.backend.service;

import com.example.backend.constants.ExceptionStatus;
import com.example.backend.constants.ExceptionType;
import com.example.backend.constants.OrderStatus;
import com.example.backend.constants.OrderType;
import com.example.backend.constants.RouteStatus;
import com.example.backend.dao.DeliveryRecordsDAO;
import com.example.backend.dao.DriversDAO;
import com.example.backend.dao.ExceptionCasesDAO;
import com.example.backend.dao.OrdersDAO;
import com.example.backend.dao.RoutesDAO;
import com.example.backend.dto.request.ArriveRequestDTO;
import com.example.backend.dto.request.LoadingItemDTO;
import com.example.backend.dto.request.LoadingRequestDTO;
import com.example.backend.dto.request.LoadingMismatchRequestDTO;
import com.example.backend.dto.request.NoSignatureRequestDTO;
import com.example.backend.dto.respones.LoadingResponse;
import com.example.backend.entity.DeliveryRecordsEntity;
import com.example.backend.entity.DriversEntity;
import com.example.backend.entity.ExceptionCasesEntity;
import com.example.backend.entity.OrderItemsEntity;
import com.example.backend.entity.OrdersEntity;
import com.example.backend.entity.RoutesEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 倉庫點交與抵達門市。
 *
 * <p>DAO 全部用 mock。司機 7 號今天有一條已發布的路線 30，上面一張 12 箱的訂單 41。</p>
 */
class DeliveryServiceTest {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final long DRIVER_ID = 7L;
    private static final long ROUTE_ID = 30L;
    private static final long ORDER_ID = 41L;
    private static final long FOLLOW_UP_ORDER_ID = 99L;
    private static final long EXCEPTION_CASE_ID = 501L;

    private DeliveryRecordsDAO deliveryRecordsDAO;
    private ExceptionCasesDAO exceptionCasesDAO;
    private OrdersDAO ordersDAO;
    private DriversDAO driversDAO;
    private OrdersEntity order;
    private DeliveryService service;

    private PreTripInspectionService preTripInspectionService;

    @BeforeEach
    void setUp() {
        deliveryRecordsDAO = mock(DeliveryRecordsDAO.class);
        exceptionCasesDAO = mock(ExceptionCasesDAO.class);
        ordersDAO = mock(OrdersDAO.class);
        driversDAO = mock(DriversDAO.class);
        RoutesDAO routesDAO = mock(RoutesDAO.class);

        givenActiveDriver(DRIVER_ID);

        RoutesEntity route = new RoutesEntity();
        route.setId(ROUTE_ID);
        route.setDate(LocalDate.now(TAIPEI));
        route.setDriverId(DRIVER_ID);
        route.setStatus(RouteStatus.PUBLISHED);
        when(routesDAO.findById(ROUTE_ID)).thenReturn(Optional.of(route));

        order = new OrdersEntity();
        order.setId(ORDER_ID);
        order.setOrderNumber("DO-001");
        order.setRouteId(ROUTE_ID);
        order.setAssignedDriverId(DRIVER_ID);
        order.setStoreId(3L);
        order.setWarehouseId(1L);
        order.setBoxCount(12);
        order.setDeliveryDate(LocalDate.now(TAIPEI));
        order.setStatus(OrderStatus.CONFIRMED);
        when(ordersDAO.findForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
        // load 先用不鎖的 findById 查路線、確認安全檢查，再鎖訂單；安全檢查另有自己的測試，這裡一律當作通過
        when(ordersDAO.findById(ORDER_ID)).thenReturn(Optional.of(order));
        preTripInspectionService = mock(PreTripInspectionService.class);

        // 新建的訂單（補送單）存檔時才給 id，原單存檔照原樣回傳
        when(ordersDAO.save(any(OrdersEntity.class))).thenAnswer(invocation -> {
            OrdersEntity saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(FOLLOW_UP_ORDER_ID);
            }
            return saved;
        });
        when(exceptionCasesDAO.save(any(ExceptionCasesEntity.class))).thenAnswer(invocation -> {
            ExceptionCasesEntity saved = invocation.getArgument(0);
            saved.setId(EXCEPTION_CASE_ID);
            return saved;
        });
        when(deliveryRecordsDAO.save(any(DeliveryRecordsEntity.class))).thenAnswer(invocation -> {
            DeliveryRecordsEntity saved = invocation.getArgument(0);
            saved.setId(80L);
            return saved;
        });

        // 路段里程在抵達時記錄，屬於 RouteLegMileageService 自己的測試範圍，這裡只要不出錯就好
        service = new DeliveryService(deliveryRecordsDAO, exceptionCasesDAO, ordersDAO, routesDAO, driversDAO,
                mock(RouteLegMileageService.class), preTripInspectionService);
    }

    @Test
    void 點交箱數相符_轉為已點交並記下時間() {
        LoadingResponse response = service.load(DRIVER_ID, loading(12, null));

        assertEquals(OrderStatus.LOADED, order.getStatus());
        assertNotNull(order.getLoadedAt());
        assertEquals(OrderStatus.LOADED, response.getOrderStatus());
        assertEquals(order.getLoadedAt(), response.getLoadedAt());
        assertNull(response.getExceptionCaseId());
        assertNull(response.getFollowUpOrderId());
        verify(exceptionCasesDAO, never()).save(any());
    }

    @Test
    void 安全檢查沒通過_不能點交_訂單也不會被鎖() {
        when(preTripInspectionService.requirePassed(DRIVER_ID, ROUTE_ID))
                .thenThrow(new IllegalArgumentException("出車前安全檢查沒有通過，請聯絡主管處理"));

        assertThrows(IllegalArgumentException.class, () -> service.load(DRIVER_ID, loading(12, null)));

        assertEquals(OrderStatus.CONFIRMED, order.getStatus());
        // 先確認檢查再鎖訂單：檢查沒過就不會走到鎖訂單那一步
        verify(ordersDAO, never()).findForUpdate(ORDER_ID);
    }

    @Test
    void 有內容物的訂單_逐項勾選數量相符才完成點交() {
        OrderItemsEntity milkTea = orderItem(601L, "奶茶", 3, "箱");
        OrderItemsEntity blackTea = orderItem(602L, "紅茶", 5, "箱");
        order.addItem(milkTea);
        order.addItem(blackTea);
        LoadingRequestDTO request = loading(12, null);
        request.setItems(List.of(loadingItem(601L, true, 3), loadingItem(602L, true, 5)));

        LoadingResponse response = service.load(DRIVER_ID, request);

        assertEquals(OrderStatus.LOADED, response.getOrderStatus());
        assertTrue(response.getItemChecklistCompleted());
        assertEquals(2, response.getCheckedItemCount());
        assertEquals(2, response.getTotalItemCount());
        assertEquals(3, milkTea.getLoadedQuantity());
        assertEquals(DRIVER_ID, milkTea.getCheckedByDriverId());
        assertNotNull(milkTea.getCheckedAt());
    }

    @Test
    void 有內容物但漏勾一項_不能點交上車() {
        order.addItem(orderItem(601L, "奶茶", 3, "箱"));
        order.addItem(orderItem(602L, "紅茶", 5, "箱"));
        LoadingRequestDTO request = loading(12, null);
        request.setItems(List.of(loadingItem(601L, true, 3)));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.load(DRIVER_ID, request));

        assertTrue(exception.getMessage().contains("紅茶"), exception.getMessage());
        assertEquals(OrderStatus.CONFIRMED, order.getStatus());
        verify(ordersDAO, never()).save(any());
    }

    @Test
    void 內容物實點短少_建立點交異常並保留逐項結果() {
        order.addItem(orderItem(601L, "奶茶", 3, "箱"));
        order.addItem(orderItem(602L, "紅茶", 5, "箱"));
        LoadingRequestDTO request = loading(12, "紅茶短少");
        request.setItems(List.of(loadingItem(601L, true, 3), loadingItem(602L, true, 4)));

        LoadingResponse response = service.load(DRIVER_ID, request);

        assertEquals(OrderStatus.FAILED, response.getOrderStatus());
        assertFalse(response.getItemChecklistCompleted());
        assertEquals(1, response.getCheckedItemCount());
        assertEquals(2, response.getTotalItemCount());
        ArgumentCaptor<ExceptionCasesEntity> savedCase = ArgumentCaptor.forClass(ExceptionCasesEntity.class);
        verify(exceptionCasesDAO).save(savedCase.capture());
        assertTrue(savedCase.getValue().getDescription().contains("紅茶 應到 5箱、實點 4箱"),
                savedCase.getValue().getDescription());
    }

    @Test
    void 點交箱數不符_原單配送失敗_建立異常單與明日補送單() {
        LocalDateTime before = LocalDateTime.now(TAIPEI);

        LoadingResponse response = service.load(DRIVER_ID, loading(10, " 少兩箱 "));

        assertEquals(OrderStatus.FAILED, order.getStatus());
        assertNull(order.getLoadedAt());

        ArgumentCaptor<OrdersEntity> savedOrders = ArgumentCaptor.forClass(OrdersEntity.class);
        verify(ordersDAO, times(2)).save(savedOrders.capture());
        OrdersEntity followUp = savedOrders.getAllValues().stream()
                .filter(saved -> saved != order)
                .findFirst()
                .orElseThrow();
        LocalDate tomorrow = LocalDate.now(TAIPEI).plusDays(1);
        assertEquals(OrderStatus.PENDING_CONFIRM, followUp.getStatus());
        assertEquals(OrderType.REDELIVERY, followUp.getOrderType());
        assertEquals(ORDER_ID, followUp.getParentOrderId());
        assertEquals(1, followUp.getRetryCount());
        assertEquals(12, followUp.getBoxCount(), "整張重送，箱數跟原單一樣");
        assertEquals(tomorrow, followUp.getDeliveryDate());
        assertEquals("LD-" + tomorrow.format(DateTimeFormatter.BASIC_ISO_DATE).substring(2)
                + "-" + Long.toString(ORDER_ID, 36).toUpperCase() + "-1", followUp.getOrderNumber());

        ArgumentCaptor<ExceptionCasesEntity> savedCase = ArgumentCaptor.forClass(ExceptionCasesEntity.class);
        verify(exceptionCasesDAO).save(savedCase.capture());
        ExceptionCasesEntity exceptionCase = savedCase.getValue();
        assertEquals(ExceptionType.LOADING_MISMATCH, exceptionCase.getType());
        assertEquals(ExceptionStatus.OPEN, exceptionCase.getStatus());
        assertEquals(ORDER_ID, exceptionCase.getOrderId());
        assertEquals(FOLLOW_UP_ORDER_ID, exceptionCase.getFollowUpOrderId());
        // 送審時間是現在，異常中心下一次查詢就會列出來，不用等到隔天
        assertFalse(exceptionCase.getReviewAvailableAt().isBefore(before));
        assertFalse(exceptionCase.getReviewAvailableAt().isAfter(LocalDateTime.now(TAIPEI)));
        assertTrue(exceptionCase.getDescription().contains("應到 12 箱，實點 10 箱"), exceptionCase.getDescription());
        assertTrue(exceptionCase.getDescription().endsWith("司機備註：少兩箱"), exceptionCase.getDescription());

        assertEquals(OrderStatus.FAILED, response.getOrderStatus());
        assertEquals(EXCEPTION_CASE_ID, response.getExceptionCaseId());
        assertEquals(FOLLOW_UP_ORDER_ID, response.getFollowUpOrderId());
        assertEquals(followUp.getOrderNumber(), response.getFollowUpOrderNumber());
        assertEquals(tomorrow, response.getFollowUpDeliveryDate());
    }

    @Test
    void 按商品點交不符_立即送原有異常類型且不偽造實點數量() {
        OrderItemsEntity milk = orderItem(601L, "鮮乳", 4, "箱");
        OrderItemsEntity bread = orderItem(602L, "麵包", 8, "箱");
        order.addItem(milk);
        order.addItem(bread);
        var response = service.reportLoadingMismatch(DRIVER_ID,
                new LoadingMismatchRequestDTO(ORDER_ID, 601L, "包装破損"));
        assertEquals(OrderStatus.FAILED, response.getOrderStatus());
        assertEquals(OrderStatus.FAILED, order.getStatus());
        assertNull(order.getLoadedAt());
        assertTrue(milk.isLoadingMismatchReported());
        assertNull(milk.getLoadedQuantity());
        assertNotNull(milk.getCheckedAt());
        assertEquals(DRIVER_ID, milk.getCheckedByDriverId());
        assertFalse(bread.isLoadingMismatchReported());
        assertNull(bread.getLoadedQuantity());
        assertNull(bread.getCheckedAt());
        assertNull(response.getItems().getFirst().getLoadedQuantity());
        assertEquals(2, response.getTotalItemCount());
        assertEquals(0, response.getCheckedItemCount());
        assertFalse(response.getItemChecklistCompleted());
        var savedCase = ArgumentCaptor.forClass(ExceptionCasesEntity.class);
        verify(exceptionCasesDAO).save(savedCase.capture());
        assertEquals(ExceptionType.LOADING_MISMATCH, savedCase.getValue().getType());
        assertTrue(savedCase.getValue().getDescription().contains("商品「鮮乳」應點 4箱"));
        assertEquals(FOLLOW_UP_ORDER_ID, savedCase.getValue().getFollowUpOrderId());
        var savedOrders = ArgumentCaptor.forClass(OrdersEntity.class);
        verify(ordersDAO, times(2)).save(savedOrders.capture());
        var rebuilt = savedOrders.getAllValues().stream().filter(saved -> saved != order).findFirst().orElseThrow();
        assertEquals(OrderStatus.PENDING_CONFIRM, rebuilt.getStatus());
        assertEquals(ORDER_ID, rebuilt.getParentOrderId());
        assertEquals(2, rebuilt.getItems().size());
        for (var item : rebuilt.getItems()) {
            assertFalse(item.isLoadingMismatchReported());
            assertNull(item.getLoadedQuantity());
            assertNull(item.getCheckedAt());
        }
        // 第二次請求會在原單狀態鎖擋下；不能重複建異常與重建單。
        assertThrows(IllegalArgumentException.class, () -> service.reportLoadingMismatch(DRIVER_ID,
                new LoadingMismatchRequestDTO(ORDER_ID, 601L, null)));
        verify(exceptionCasesDAO, times(1)).save(any());
    }

    @Test
    void 不屬於此訂單的商品_不能回報點交不符() {
        order.addItem(orderItem(601L, "鮮乳", 12, "箱"));
        assertThrows(IllegalArgumentException.class, () -> service.reportLoadingMismatch(DRIVER_ID,
                new LoadingMismatchRequestDTO(ORDER_ID, 999L, null)));
        assertEquals(OrderStatus.CONFIRMED, order.getStatus());
        verify(exceptionCasesDAO, never()).save(any());
        verify(ordersDAO, never()).save(any());
    }

    @Test
    void 多項商品不符_一起建立一張異常及一張重建單_其餘未勾不當相符() {
        var milk = orderItem(601L, "鮮乳", 4, "箱");
        var bread = orderItem(602L, "麵包", 3, "箱");
        var water = orderItem(603L, "飲用水", 5, "箱");
        order.addItem(milk); order.addItem(bread); order.addItem(water);
        var result = service.reportLoadingMismatch(DRIVER_ID,
                new LoadingMismatchRequestDTO(ORDER_ID, null, List.of(601L, 602L), null));
        assertEquals(OrderStatus.FAILED, result.getOrderStatus());
        assertEquals(3, result.getTotalItemCount());
        assertEquals(2, result.getItems().size());
        assertTrue(milk.isLoadingMismatchReported());
        assertTrue(bread.isLoadingMismatchReported());
        assertFalse(water.isLoadingMismatchReported());
        assertNull(water.getCheckedAt());
        for (var item : result.getItems()) assertNull(item.getLoadedQuantity());
        var savedCase = ArgumentCaptor.forClass(ExceptionCasesEntity.class);
        verify(exceptionCasesDAO, times(1)).save(savedCase.capture());
        assertTrue(savedCase.getValue().getDescription().contains("鮮乳"));
        assertTrue(savedCase.getValue().getDescription().contains("麵包"));
        verify(ordersDAO, times(2)).save(any());
    }

    @Test
    void 部分商品不屬於此訂單_全部驗證完成前不能先寫入其他商品() {
        var milk = orderItem(601L, "鮮乳", 12, "箱");
        order.addItem(milk);
        assertThrows(IllegalArgumentException.class, () -> service.reportLoadingMismatch(DRIVER_ID,
                new LoadingMismatchRequestDTO(ORDER_ID, null, List.of(601L, 999L), null)));
        assertFalse(milk.isLoadingMismatchReported());
        assertNull(milk.getCheckedAt());
        assertEquals(OrderStatus.CONFIRMED, order.getStatus());
        verify(exceptionCasesDAO, never()).save(any());
        verify(ordersDAO, never()).save(any());
    }

    @Test
    void 沒勾商品或重複商品_不能送異常() {
        order.addItem(orderItem(601L, "鮮乳", 12, "箱"));
        assertThrows(IllegalArgumentException.class, () -> service.reportLoadingMismatch(DRIVER_ID,
                new LoadingMismatchRequestDTO(ORDER_ID, null, List.of(), null)));
        assertThrows(IllegalArgumentException.class, () -> service.reportLoadingMismatch(DRIVER_ID,
                new LoadingMismatchRequestDTO(ORDER_ID, null, List.of(601L, 601L), null)));
        verify(exceptionCasesDAO, never()).save(any());
        verify(ordersDAO, never()).save(any());
    }

    @Test
    void 商品很多時_異常摘要不溢位但所有商品仍保留不符旗標() {
        for (long id = 1; id <= 20; id++) order.addItem(orderItem(id, "長商品名稱".repeat(18), 1, "箱"));
        var ids = order.getItems().stream().map(OrderItemsEntity::getId).toList();
        service.reportLoadingMismatch(DRIVER_ID, new LoadingMismatchRequestDTO(ORDER_ID, null, ids, "備註".repeat(250)));
        var savedCase = ArgumentCaptor.forClass(ExceptionCasesEntity.class);
        verify(exceptionCasesDAO).save(savedCase.capture());
        assertTrue(savedCase.getValue().getDescription().length() <= 1000);
        assertTrue(savedCase.getValue().getDescription().contains("20 項商品"));
        assertTrue(order.getItems().stream().allMatch(OrderItemsEntity::isLoadingMismatchReported));
    }

    @Test
    void 商品點交不符_也必須通過安全檢查() {
        when(preTripInspectionService.requirePassed(DRIVER_ID, ROUTE_ID))
                .thenThrow(new IllegalArgumentException("安全檢查未通過"));
        assertThrows(IllegalArgumentException.class, () -> service.reportLoadingMismatch(DRIVER_ID,
                new LoadingMismatchRequestDTO(ORDER_ID, 601L, null)));
        verify(ordersDAO, never()).findForUpdate(ORDER_ID);
        verify(exceptionCasesDAO, never()).save(any());
    }

    @Test
    void 點交零箱_整張沒貨也走不符流程() {
        LoadingResponse response = service.load(DRIVER_ID, loading(0, null));

        assertEquals(OrderStatus.FAILED, response.getOrderStatus());
        assertNotNull(response.getExceptionCaseId());
    }

    @Test
    void 實點比應到多_擋下且不改任何資料() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.load(DRIVER_ID, loading(13, null)));

        assertTrue(e.getMessage().contains("12"), e.getMessage());
        assertEquals(OrderStatus.CONFIRMED, order.getStatus());
        verify(ordersDAO, never()).save(any());
        verify(exceptionCasesDAO, never()).save(any());
    }

    @Test
    void 已經點交過的單_不能再點交() {
        order.setStatus(OrderStatus.LOADED);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.load(DRIVER_ID, loading(12, null)));

        assertTrue(e.getMessage().contains("LOADED"), e.getMessage());
        verify(ordersDAO, never()).save(any());
    }

    @Test
    void 別的司機的單_不能點交() {
        givenActiveDriver(8L);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.load(8L, loading(12, null)));

        assertEquals("這張訂單不屬於目前登入的司機", e.getMessage());
        assertEquals(OrderStatus.CONFIRMED, order.getStatus());
        verify(ordersDAO, never()).save(any());
    }

    @Test
    void 沒點交不能抵達() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.arrive(DRIVER_ID, arrive()));

        assertTrue(e.getMessage().contains("只有已點交的訂單可以登記抵達"), e.getMessage());
        assertEquals(OrderStatus.CONFIRMED, order.getStatus());
    }

    @Test
    void 點交後抵達_轉為配送中() {
        order.setStatus(OrderStatus.LOADED);
        when(deliveryRecordsDAO.findFirstByOrderIdOrderByIdDesc(ORDER_ID)).thenReturn(Optional.empty());

        service.arrive(DRIVER_ID, arrive());

        assertEquals(OrderStatus.IN_DELIVERY, order.getStatus());
        verify(deliveryRecordsDAO).save(any(DeliveryRecordsEntity.class));
    }

    @Test
    void 無人簽收的重送單號仍是NS開頭() {
        order.setStatus(OrderStatus.IN_DELIVERY);
        DeliveryRecordsEntity inProgress = new DeliveryRecordsEntity();
        inProgress.setId(80L);
        inProgress.setOrderId(ORDER_ID);
        inProgress.setArrivedAt(LocalDateTime.now(TAIPEI));
        inProgress.setNoSignature(false);
        when(deliveryRecordsDAO.findFirstByOrderIdOrderByIdDesc(ORDER_ID)).thenReturn(Optional.of(inProgress));
        NoSignatureRequestDTO request = new NoSignatureRequestDTO();
        request.setOrderId(ORDER_ID);

        var response = service.noSignature(DRIVER_ID, request);

        assertEquals(OrderStatus.NO_SIGNATURE, order.getStatus());
        assertTrue(response.getFollowUpOrderNumber().startsWith("NS-"), response.getFollowUpOrderNumber());
    }

    @Test
    void 司機回報結案改期_原單FAILED_補送單DR開頭直接進待排車_抵達紀錄補上處理時間() {
        order.setStatus(OrderStatus.IN_DELIVERY);
        DeliveryRecordsEntity inProgress = new DeliveryRecordsEntity();
        inProgress.setId(80L);
        inProgress.setOrderId(ORDER_ID);
        inProgress.setArrivedAt(LocalDateTime.now(TAIPEI).minusMinutes(5));
        inProgress.setNoSignature(false);
        when(deliveryRecordsDAO.findFirstByOrderIdOrderByIdDesc(ORDER_ID)).thenReturn(Optional.of(inProgress));
        LocalDate tomorrow = LocalDate.now(TAIPEI).plusDays(1);
        LocalDateTime now = LocalDateTime.now(TAIPEI);

        OrdersEntity followUp = service.redeliverAfterDriverReport(order, tomorrow, now);

        assertEquals(OrderStatus.FAILED, order.getStatus());
        assertEquals(OrderStatus.CONFIRMED, followUp.getStatus(), "主管結案時已決定補送，不再走待確認");
        assertEquals(OrderType.REDELIVERY, followUp.getOrderType());
        assertEquals(ORDER_ID, followUp.getParentOrderId());
        assertEquals(tomorrow, followUp.getDeliveryDate());
        assertTrue(followUp.getOrderNumber().startsWith("DR-"), followUp.getOrderNumber());
        assertEquals(now, inProgress.getHandledAt());
        assertNull(inProgress.getDeliveredAt(), "沒有送達，不能被報表算成已交貨");
    }

    private void givenActiveDriver(long driverId) {
        DriversEntity driver = new DriversEntity();
        driver.setId(driverId);
        driver.setIsActive(true);
        when(driversDAO.findById(driverId)).thenReturn(Optional.of(driver));
    }

    private LoadingRequestDTO loading(int loadedBoxCount, String notes) {
        LoadingRequestDTO request = new LoadingRequestDTO();
        request.setOrderId(ORDER_ID);
        request.setLoadedBoxCount(loadedBoxCount);
        request.setNotes(notes);
        return request;
    }

    private OrderItemsEntity orderItem(long id, String name, int quantity, String unit) {
        OrderItemsEntity item = new OrderItemsEntity();
        item.setId(id);
        item.setItemName(name);
        item.setExpectedQuantity(quantity);
        item.setUnit(unit);
        item.setSequence((int) id);
        return item;
    }

    private LoadingItemDTO loadingItem(long id, boolean checked, Integer loadedQuantity) {
        LoadingItemDTO item = new LoadingItemDTO();
        item.setOrderItemId(id);
        item.setChecked(checked);
        item.setLoadedQuantity(loadedQuantity);
        return item;
    }

    private ArriveRequestDTO arrive() {
        ArriveRequestDTO request = new ArriveRequestDTO();
        request.setOrderId(ORDER_ID);
        return request;
    }
}
