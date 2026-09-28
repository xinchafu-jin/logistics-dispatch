package com.example.backend.service;

import com.example.backend.constants.DriverCaseCategory;
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
import com.example.backend.dto.respones.DriverCasePushEvent;
import com.example.backend.dto.respones.DriverCaseResponse;
import com.example.backend.entity.AdminUsersEntity;
import com.example.backend.entity.DriversEntity;
import com.example.backend.entity.ExceptionCasesEntity;
import com.example.backend.entity.OrdersEntity;
import com.example.backend.entity.RoutesEntity;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 司機例外回報案件的規則：建案檢查、接收、結案、誰能在什麼時候留言，以及推播內容分兩份。
 *
 * <p>DAO 全部用 mock。司機 7 號今天有一條已發布的路線 30，上面有訂單 41；訂單 42 在別條路線 31。
 * 案件的 SQL 查詢、權限、推播實際送出去的樣子，另外在 DriverCasesApiTest、DriverCasesPushTest 測。</p>
 */
class DriverCaseServiceTest {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final long DRIVER_ID = 7L;
    private static final long OTHER_DRIVER_ID = 8L;
    private static final long ROUTE_ID = 30L;
    private static final long ORDER_ON_ROUTE = 41L;
    private static final long ORDER_ON_OTHER_ROUTE = 42L;
    private static final long CASE_ID = 501L;
    private static final long ADMIN_ID = 1L;
    private static final long OTHER_ADMIN_ID = 2L;

    private ExceptionCasesDAO exceptionCasesDAO;
    private DriversDAO driversDAO;
    private RoutesDAO routesDAO;
    private AdminUsersDAO adminUsersDAO;
    private DriverMessagesService driverMessagesService;
    private ApplicationEventPublisher eventPublisher;
    private DriverCaseService service;

    @BeforeEach
    void setUp() {
        exceptionCasesDAO = mock(ExceptionCasesDAO.class);
        driversDAO = mock(DriversDAO.class);
        routesDAO = mock(RoutesDAO.class);
        OrdersDAO ordersDAO = mock(OrdersDAO.class);
        adminUsersDAO = mock(AdminUsersDAO.class);
        driverMessagesService = mock(DriverMessagesService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        service = new DriverCaseService(exceptionCasesDAO, driversDAO, routesDAO, ordersDAO,
                mock(StoresDAO.class), mock(VehiclesDAO.class), adminUsersDAO, driverMessagesService, eventPublisher);

        givenDriver(DRIVER_ID, true);
        givenTodayRoute(DRIVER_ID, ROUTE_ID);
        when(ordersDAO.findById(ORDER_ON_ROUTE)).thenReturn(Optional.of(order(ORDER_ON_ROUTE, ROUTE_ID)));
        when(ordersDAO.findById(ORDER_ON_OTHER_ROUTE)).thenReturn(Optional.of(order(ORDER_ON_OTHER_ROUTE, 31L)));
        // 新案件存檔時才給 id，跟 IDENTITY 一樣
        when(exceptionCasesDAO.save(any(ExceptionCasesEntity.class))).thenAnswer(invocation -> {
            ExceptionCasesEntity saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(CASE_ID);
            }
            return saved;
        });
    }

    // ── 建案 ──

    @Test
    void 建案_帶今天路線上的單_記下司機路線與內容() {
        DriverCaseResponse response = service.create(DRIVER_ID, request(
                DriverCaseCategory.GOODS, ORDER_ON_ROUTE, "  外箱破損  ", true, "/uploads/delivery-photos/abc.jpg"));

        ExceptionCasesEntity saved = savedCase();
        assertEquals(ExceptionType.DRIVER_REPORT, saved.getType());
        assertEquals(ExceptionStatus.OPEN, saved.getStatus());
        assertEquals(DRIVER_ID, saved.getDriverId());
        assertEquals(ROUTE_ID, saved.getRouteId(), "路線要由後端找，不從前端收");
        assertEquals(ORDER_ON_ROUTE, saved.getOrderId());
        assertEquals(DriverCaseCategory.GOODS, saved.getCategory());
        assertEquals("外箱破損", saved.getDescription(), "說明前後的空白要去掉");
        assertEquals(Boolean.TRUE, saved.getCanContinue());
        assertEquals("/uploads/delivery-photos/abc.jpg", saved.getPhotoUrl());
        assertNull(saved.getAcceptedAt(), "新案件還沒有人接收");
        assertEquals(CASE_ID, response.getId());
    }

    @Test
    void 建案_推CASE_OPENED_給司機的那份不能是後台的類別() {
        service.create(DRIVER_ID, request(DriverCaseCategory.VEHICLE, null, "爆胎", false, null));

        DriverCasePushEvent event = publishedCaseEvent();
        assertEquals(DriverMessagePushType.CASE_OPENED, event.getAdminPush().getType());
        assertEquals(CASE_ID, event.getAdminPush().getExceptionCaseId());
        assertInstanceOf(AdminDriverCaseResponse.class, event.getAdminPush().getExceptionCase());
        // Jackson 依實際類別輸出欄位：子類別的物件送給司機，接收人、回報人這些欄位就會一起送出去
        assertSame(DriverCaseResponse.class, event.getDriverPush().getExceptionCase().getClass());
        assertEquals(DRIVER_ID, event.getDriverPush().getDriverId());
    }

    @Test
    void 建案_訂單不在今天路線上_擋下也不存檔() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> service.create(
                DRIVER_ID, request(DriverCaseCategory.GOODS, ORDER_ON_OTHER_ROUTE, "少箱", true, null)));

        assertEquals("只能回報今天路線上的訂單", error.getMessage());
        verify(exceptionCasesDAO, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void 建案_今天沒排路線_不帶訂單也能建_路線是null() {
        givenNoRouteToday(DRIVER_ID);

        service.create(DRIVER_ID, request(DriverCaseCategory.PERSONAL, null, "身體不適", false, null));

        assertNull(savedCase().getRouteId());
    }

    @Test
    void 建案_今天沒排路線卻帶訂單_擋下() {
        givenNoRouteToday(DRIVER_ID);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> service.create(
                DRIVER_ID, request(DriverCaseCategory.GOODS, ORDER_ON_ROUTE, "少箱", true, null)));

        assertEquals("只能回報今天路線上的訂單", error.getMessage());
    }

    /** 只收交貨照片上傳 API 給的網址；外部網址、跳出資料夾、別的資料夾都擋 */
    @Test
    void 建案_照片不是上傳API給的網址_擋下() {
        for (String photoUrl : List.of(
                "https://evil.example/a.jpg",
                "/uploads/delivery-photos/../application.properties",
                "/uploads/delivery-photos/",
                "/uploads/delivery-photos/sub/a.jpg",
                "/uploads/driver-photos/a.jpg")) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> service.create(
                    DRIVER_ID, request(DriverCaseCategory.OTHER, null, "看照片", true, photoUrl)), photoUrl);
            assertEquals("照片網址不正確，請重新上傳照片", error.getMessage(), photoUrl);
        }
        verify(exceptionCasesDAO, never()).save(any());
    }

    @Test
    void 建案_說明全是全形空白_擋下() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> service.create(
                DRIVER_ID, request(DriverCaseCategory.OTHER, null, "　　", true, null)));

        assertEquals("請點選發生的狀況，或寫一段說明", error.getMessage());
    }

    @Test
    void 建案_帳號停用_擋下() {
        givenDriver(DRIVER_ID, false);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> service.create(
                DRIVER_ID, request(DriverCaseCategory.OTHER, null, "測試", true, null)));

        assertEquals("司機帳號目前未啟用", error.getMessage());
    }

    // ── 接收 ──

    @Test
    void 接收_寫入接收人和時間_推CASE_ACCEPTED() {
        ExceptionCasesEntity exceptionCase = givenCase(DRIVER_ID, ExceptionStatus.OPEN, null);

        AdminDriverCaseResponse response = service.accept(CASE_ID, ADMIN_ID);

        assertEquals(ADMIN_ID, exceptionCase.getAcceptedAdminId());
        assertNotNull(exceptionCase.getAcceptedAt());
        assertEquals(ADMIN_ID, response.getAcceptedAdminId());
        DriverCasePushEvent event = publishedCaseEvent();
        assertEquals(DriverMessagePushType.CASE_ACCEPTED, event.getDriverPush().getType());
        assertNotNull(event.getDriverPush().getExceptionCase().getAcceptedAt(), "司機端靠 acceptedAt 變成「處理中」");
        assertSame(DriverCaseResponse.class, event.getDriverPush().getExceptionCase().getClass());
    }

    @Test
    void 接收_已被別人接收_錯誤訊息有接收人名字_不覆蓋() {
        ExceptionCasesEntity exceptionCase = givenCase(DRIVER_ID, ExceptionStatus.OPEN, OTHER_ADMIN_ID);
        AdminUsersEntity other = new AdminUsersEntity();
        other.setId(OTHER_ADMIN_ID);
        other.setName("王主管");
        when(adminUsersDAO.findById(OTHER_ADMIN_ID)).thenReturn(Optional.of(other));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.accept(CASE_ID, ADMIN_ID));

        assertEquals("這件案件已由王主管接收", error.getMessage());
        assertEquals(OTHER_ADMIN_ID, exceptionCase.getAcceptedAdminId());
        verify(exceptionCasesDAO, never()).save(any());
    }

    @Test
    void 接收_同一位再按一次_不報錯也不重推() {
        givenCase(DRIVER_ID, ExceptionStatus.OPEN, ADMIN_ID);

        service.accept(CASE_ID, ADMIN_ID);

        verify(exceptionCasesDAO, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void 接收_要鎖住那一列() {
        givenCase(DRIVER_ID, ExceptionStatus.OPEN, null);

        service.accept(CASE_ID, ADMIN_ID);

        // 兩位管理員同時按，靠 SELECT ... FOR UPDATE 讓後按的等前一位 commit
        verify(exceptionCasesDAO).findForUpdate(CASE_ID);
        verify(exceptionCasesDAO, never()).findById(anyLong());
    }

    @Test
    void 接收_已結案_擋下() {
        givenCase(DRIVER_ID, ExceptionStatus.CLOSED, null);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.accept(CASE_ID, ADMIN_ID));

        assertEquals("這件案件已經結案", error.getMessage());
    }

    @Test
    void 不是司機回報的異常_當作找不到() {
        ExceptionCasesEntity shortage = givenCase(DRIVER_ID, ExceptionStatus.OPEN, null);
        shortage.setType(ExceptionType.SHORTAGE);

        assertThrows(EntityNotFoundException.class, () -> service.accept(CASE_ID, ADMIN_ID));
    }

    // ── 留言 ──

    @Test
    void 還沒接收就回覆_擋下() {
        givenCase(DRIVER_ID, ExceptionStatus.OPEN, null);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.sendFromAdmin(CASE_ID, ADMIN_ID, "收到"));

        assertEquals("請先在異常中心接收這件案件，再回覆司機", error.getMessage());
        verify(driverMessagesService, never()).sendCaseMessage(anyLong(), anyLong(), any(), any(), anyString());
    }

    @Test
    void 接收後_別的管理員也能回() {
        givenCase(DRIVER_ID, ExceptionStatus.OPEN, OTHER_ADMIN_ID);

        service.sendFromAdmin(CASE_ID, ADMIN_ID, "我來處理");

        verify(driverMessagesService).sendCaseMessage(DRIVER_ID, CASE_ID, MessageSender.ADMIN, ADMIN_ID, "我來處理");
    }

    @Test
    void 還沒接收_司機也能補充狀況() {
        givenCase(DRIVER_ID, ExceptionStatus.OPEN, null);

        service.sendFromDriver(DRIVER_ID, CASE_ID, "冒煙了");

        verify(driverMessagesService).sendCaseMessage(DRIVER_ID, CASE_ID, MessageSender.DRIVER, null, "冒煙了");
    }

    @Test
    void 已結案_雙方都不能留言() {
        givenCase(DRIVER_ID, ExceptionStatus.CLOSED, ADMIN_ID);

        IllegalArgumentException driver = assertThrows(IllegalArgumentException.class,
                () -> service.sendFromDriver(DRIVER_ID, CASE_ID, "還在嗎"));
        IllegalArgumentException admin = assertThrows(IllegalArgumentException.class,
                () -> service.sendFromAdmin(CASE_ID, ADMIN_ID, "補充"));

        assertEquals("這件案件已結案，不能再留言", driver.getMessage());
        assertEquals("這件案件已結案，不能再留言", admin.getMessage());
        verify(driverMessagesService, never()).sendCaseMessage(anyLong(), anyLong(), any(), any(), anyString());
    }

    @Test
    void 別的司機的案件_當作找不到() {
        givenCase(OTHER_DRIVER_ID, ExceptionStatus.OPEN, null);

        EntityNotFoundException read = assertThrows(EntityNotFoundException.class,
                () -> service.findMessagesForDriver(DRIVER_ID, CASE_ID, null));
        assertThrows(EntityNotFoundException.class, () -> service.sendFromDriver(DRIVER_ID, CASE_ID, "偷看"));
        assertThrows(EntityNotFoundException.class, () -> service.markReadByDriver(DRIVER_ID, CASE_ID));

        assertEquals("找不到案件，ID：" + CASE_ID, read.getMessage());
        verify(driverMessagesService, never()).findCaseMessages(anyLong(), anyLong(), any());
    }

    // ── 結案 ──

    @Test
    void 結案_沒接收也能結_寫處理結果_推CASE_CLOSED() {
        ExceptionCasesEntity exceptionCase = givenCase(DRIVER_ID, ExceptionStatus.OPEN, null);

        service.close(CASE_ID, "王主管", "  重複回報  ");

        assertEquals(ExceptionStatus.CLOSED, exceptionCase.getStatus());
        assertEquals("王主管", exceptionCase.getHandledBy());
        assertEquals("重複回報", exceptionCase.getResolution());
        assertNotNull(exceptionCase.getHandledAt());
        assertEquals(DriverMessagePushType.CASE_CLOSED, publishedCaseEvent().getDriverPush().getType());
    }

    @Test
    void 結案_處理結果空白_擋下() {
        givenCase(DRIVER_ID, ExceptionStatus.OPEN, ADMIN_ID);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.close(CASE_ID, "王主管", "   "));

        assertEquals("處理結果不能為空", error.getMessage());
        verify(exceptionCasesDAO, never()).save(any());
    }

    @Test
    void 舊版回報沒有司機_也能結案_只推後台() {
        givenCase(null, ExceptionStatus.OPEN, null);

        service.close(CASE_ID, "王主管", "舊資料清理");

        DriverCasePushEvent event = publishedCaseEvent();
        assertNotNull(event.getAdminPush());
        assertNull(event.getDriverPush());
    }

    // ── 異常中心清單 ──

    @Test
    void 異常中心排序_沒人接收的在前_同組裡不能繼續的在前_再來先回報的在前() {
        ExceptionCasesEntity acceptedStuck = openCase(1L, false, ADMIN_ID);
        ExceptionCasesEntity waitingFine = openCase(2L, true, null);
        ExceptionCasesEntity waitingStuck = openCase(3L, false, null);
        ExceptionCasesEntity waitingStuckLater = openCase(4L, false, null);
        when(exceptionCasesDAO.findByTypeAndStatusOrderByIdAsc(ExceptionType.DRIVER_REPORT, ExceptionStatus.OPEN))
                .thenReturn(List.of(acceptedStuck, waitingFine, waitingStuck, waitingStuckLater));

        List<AdminDriverCaseResponse> cases = service.findForAdmin(null);

        assertEquals(List.of(3L, 4L, 2L, 1L), cases.stream().map(AdminDriverCaseResponse::getId).toList());
    }

    // ── 測試資料 ──

    private DriverCaseRequestDTO request(DriverCaseCategory category, Long orderId, String description,
                                         Boolean canContinue, String photoUrl) {
        DriverCaseRequestDTO request = new DriverCaseRequestDTO();
        request.setCategory(category);
        request.setOrderId(orderId);
        request.setDescription(description);
        request.setCanContinue(canContinue);
        request.setPhotoUrl(photoUrl);
        return request;
    }

    private void givenDriver(long driverId, boolean active) {
        DriversEntity driver = new DriversEntity();
        driver.setId(driverId);
        driver.setName("司機" + driverId);
        driver.setIsActive(active);
        when(driversDAO.findById(driverId)).thenReturn(Optional.of(driver));
    }

    private void givenTodayRoute(long driverId, long routeId) {
        RoutesEntity route = new RoutesEntity();
        route.setId(routeId);
        route.setDate(LocalDate.now(TAIPEI));
        route.setDriverId(driverId);
        route.setStatus(RouteStatus.PUBLISHED);
        when(routesDAO.findByDateAndDriverIdAndStatusOrderByIdAsc(
                LocalDate.now(TAIPEI), driverId, RouteStatus.PUBLISHED)).thenReturn(List.of(route));
    }

    private void givenNoRouteToday(long driverId) {
        when(routesDAO.findByDateAndDriverIdAndStatusOrderByIdAsc(
                LocalDate.now(TAIPEI), driverId, RouteStatus.PUBLISHED)).thenReturn(List.of());
    }

    private OrdersEntity order(long orderId, long routeId) {
        OrdersEntity order = new OrdersEntity();
        order.setId(orderId);
        order.setRouteId(routeId);
        order.setStoreId(3L);
        return order;
    }

    /** 案件 501；鎖與不鎖兩種查法都回同一個物件，測試才看得到 Service 改了什麼 */
    private ExceptionCasesEntity givenCase(Long driverId, ExceptionStatus status, Long acceptedAdminId) {
        ExceptionCasesEntity exceptionCase = openCase(CASE_ID, true, acceptedAdminId);
        exceptionCase.setDriverId(driverId);
        exceptionCase.setStatus(status);
        when(exceptionCasesDAO.findForUpdate(CASE_ID)).thenReturn(Optional.of(exceptionCase));
        when(exceptionCasesDAO.findById(CASE_ID)).thenReturn(Optional.of(exceptionCase));
        return exceptionCase;
    }

    private ExceptionCasesEntity openCase(long id, boolean canContinue, Long acceptedAdminId) {
        ExceptionCasesEntity exceptionCase = new ExceptionCasesEntity();
        exceptionCase.setId(id);
        exceptionCase.setType(ExceptionType.DRIVER_REPORT);
        exceptionCase.setStatus(ExceptionStatus.OPEN);
        exceptionCase.setDriverId(DRIVER_ID);
        exceptionCase.setCategory(DriverCaseCategory.VEHICLE);
        exceptionCase.setDescription("測試案件");
        exceptionCase.setCanContinue(canContinue);
        exceptionCase.setCreatedAt(LocalDateTime.now(TAIPEI));
        if (acceptedAdminId != null) {
            exceptionCase.setAcceptedAdminId(acceptedAdminId);
            exceptionCase.setAcceptedAt(LocalDateTime.now(TAIPEI));
        }
        return exceptionCase;
    }

    private ExceptionCasesEntity savedCase() {
        ArgumentCaptor<ExceptionCasesEntity> captor = ArgumentCaptor.forClass(ExceptionCasesEntity.class);
        verify(exceptionCasesDAO).save(captor.capture());
        return captor.getValue();
    }

    private DriverCasePushEvent publishedCaseEvent() {
        ArgumentCaptor<DriverCasePushEvent> captor = ArgumentCaptor.forClass(DriverCasePushEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        return captor.getValue();
    }
}
