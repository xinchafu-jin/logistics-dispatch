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
import com.example.backend.dto.request.LoadingRequestDTO;
import com.example.backend.dto.request.NoSignatureRequestDTO;
import com.example.backend.dto.respones.LoadingResponse;
import com.example.backend.entity.DeliveryRecordsEntity;
import com.example.backend.entity.DriversEntity;
import com.example.backend.entity.ExceptionCasesEntity;
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
                mock(RouteLegMileageService.class));
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

    private ArriveRequestDTO arrive() {
        ArriveRequestDTO request = new ArriveRequestDTO();
        request.setOrderId(ORDER_ID);
        return request;
    }
}
