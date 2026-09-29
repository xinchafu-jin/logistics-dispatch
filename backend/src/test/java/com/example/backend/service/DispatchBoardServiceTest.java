package com.example.backend.service;

import com.example.backend.constants.OrderStatus;
import com.example.backend.constants.ExceptionStatus;
import com.example.backend.constants.ExceptionType;
import com.example.backend.constants.RouteStatus;
import com.example.backend.dao.ExceptionCasesDAO;
import com.example.backend.dao.DriversDAO;
import com.example.backend.dao.OrdersDAO;
import com.example.backend.dao.RoutesDAO;
import com.example.backend.dao.StoresDAO;
import com.example.backend.dao.VehiclesDAO;
import com.example.backend.dao.WarehousesDAO;
import com.example.backend.dto.respones.DispatchResponse;
import com.example.backend.entity.OrdersEntity;
import com.example.backend.entity.ExceptionCasesEntity;
import com.example.backend.entity.RoutesEntity;
import com.example.backend.entity.StoresEntity;
import com.example.backend.entity.WarehousesEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 看板要把待確認的單另外列出來，不能混進待排單。
 *
 * <p>倉庫 1 當天還沒有路線：一張已確認的單送 A 店，一張待確認的補送單送 B 店。</p>
 */
class DispatchBoardServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 9, 26);
    private static final Long WAREHOUSE = 1L;

    private StoresDAO storesDAO;
    private OrdersDAO ordersDAO;
    private RoutesDAO routesDAO;
    private ExceptionCasesDAO casesDAO;
    private DispatchBoardService service;

    @BeforeEach
    void setUp() {
        ordersDAO = mock(OrdersDAO.class);
        routesDAO = mock(RoutesDAO.class);
        storesDAO = mock(StoresDAO.class);
        VehiclesDAO vehiclesDAO = mock(VehiclesDAO.class);
        DriversDAO driversDAO = mock(DriversDAO.class);
        WarehousesDAO warehousesDAO = mock(WarehousesDAO.class);
        casesDAO = mock(ExceptionCasesDAO.class);

        WarehousesEntity warehouse = new WarehousesEntity();
        warehouse.setId(WAREHOUSE);
        warehouse.setName("台北倉");
        when(warehousesDAO.findById(WAREHOUSE)).thenReturn(Optional.of(warehouse));

        when(ordersDAO.findByDeliveryDateAndStatusAndWarehouseIdAndRouteIdIsNull(
                DATE, OrderStatus.CONFIRMED, WAREHOUSE))
                .thenReturn(List.of(order(1L, "DO-219", 101L, OrderStatus.CONFIRMED)));
        when(ordersDAO.findByDeliveryDateAndStatusAndWarehouseIdAndRouteIdIsNull(
                DATE, OrderStatus.PENDING_CONFIRM, WAREHOUSE))
                .thenReturn(List.of(order(2L, "DO-LD-105", 102L, OrderStatus.PENDING_CONFIRM)));

        List<StoresEntity> stores = List.of(store(101L, "A 店"), store(102L, "B 店"));
        // 只回傳這次有要的門市，漏收 storeId 的話對應的單就拿不到門市
        when(storesDAO.findAllById(any())).thenAnswer(invocation -> {
            Collection<?> ids = invocation.getArgument(0);
            List<StoresEntity> found = new ArrayList<>();
            for (StoresEntity store : stores) {
                if (ids.contains(store.getId())) {
                    found.add(store);
                }
            }
            return found;
        });
        when(vehiclesDAO.findAllById(any())).thenReturn(List.of());
        when(driversDAO.findAllById(any())).thenReturn(List.of());

        service = new DispatchBoardService(
                ordersDAO, routesDAO, storesDAO, vehiclesDAO, driversDAO, warehousesDAO, casesDAO);
    }

    @Test
    void 待確認的單另外列出_不混進待排單() {
        DispatchResponse board = service.getBoard(DATE, WAREHOUSE);

        assertEquals(List.of("DO-219"),
                board.getUnassignedOrders().stream().map(DispatchResponse.UnassignedOrderResponse::getOrderNumber).toList());
        assertEquals(List.of("DO-LD-105"),
                board.getPendingConfirmOrders().stream().map(DispatchResponse.UnassignedOrderResponse::getOrderNumber).toList());
    }

    @Test
    void 待確認的單也帶門市_而且門市只查一次() {
        DispatchResponse board = service.getBoard(DATE, WAREHOUSE);

        assertEquals("B 店", board.getPendingConfirmOrders().get(0).getStoreName());
        assertEquals("A 店", board.getUnassignedOrders().get(0).getStoreName());
        verify(storesDAO, times(1)).findAllById(any());
    }

    @Test
    void 無人簽收待自動送單在看板標示時間_不顯示一般確認動作() {
        ExceptionCasesEntity incident = new ExceptionCasesEntity();
        incident.setFollowUpOrderId(2L);
        incident.setType(ExceptionType.NO_SIGNATURE);
        incident.setReviewAvailableAt(LocalDateTime.of(2026, 9, 27, 6, 0));
        when(casesDAO.findByFollowUpOrderIdInAndStatus(
                List.of(2L), ExceptionStatus.OPEN))
                .thenReturn(List.of(incident));

        DispatchResponse board = service.getBoard(DATE, WAREHOUSE);

        assertEquals(true, board.getPendingConfirmOrders().getFirst().isAwaitingAutomaticDispatch());
        assertEquals(incident.getReviewAvailableAt(),
                board.getPendingConfirmOrders().getFirst().getAutoDispatchAt());
    }

    @Test
    void 未結異常後續單在看板提示異常中心確認() {
        ExceptionCasesEntity incident = new ExceptionCasesEntity();
        incident.setFollowUpOrderId(2L);
        incident.setType(ExceptionType.UNSETTLED_ORDER);
        when(casesDAO.findByFollowUpOrderIdInAndStatus(List.of(2L), ExceptionStatus.OPEN))
                .thenReturn(List.of(incident));

        DispatchResponse board = service.getBoard(DATE, WAREHOUSE);

        assertEquals(true, board.getPendingConfirmOrders().getFirst().isAwaitingExceptionReview());
        assertEquals(false, board.getPendingConfirmOrders().getFirst().isAwaitingAutomaticDispatch());
    }

    @Test
    void 舊日未排單已有未結異常時不再只標成待排車() {
        ExceptionCasesEntity incident = new ExceptionCasesEntity();
        incident.setOrderId(1L);
        incident.setType(ExceptionType.UNSETTLED_ORDER);
        when(casesDAO.findByOrderIdInAndTypeAndStatus(
                List.of(1L), ExceptionType.UNSETTLED_ORDER, ExceptionStatus.OPEN))
                .thenReturn(List.of(incident));

        DispatchResponse board = service.getBoard(DATE, WAREHOUSE);

        assertEquals(true, board.getUnassignedOrders().getFirst().isAwaitingExceptionReview());
    }

    @Test
    void 已結案的舊路線失敗單不再顯示待處理提醒() {
        RoutesEntity route = new RoutesEntity();
        route.setId(8L);
        route.setVehicleId(3L);
        route.setStatus(RouteStatus.PUBLISHED);
        when(routesDAO.findByDateAndWarehouseId(DATE, WAREHOUSE)).thenReturn(List.of(route));
        OrdersEntity failed = order(5L, "DO-FAILED", 101L, OrderStatus.FAILED);
        when(ordersDAO.findByRouteIdAndStatusInOrderBySequence(any(), any()))
                .thenReturn(List.of(failed));

        DispatchResponse board = service.getBoard(DATE, WAREHOUSE);

        assertEquals(false, board.getRoutes().getFirst().getStops().getFirst().isOpenException());
        verify(casesDAO).findByOrderIdInAndStatus(List.of(5L), ExceptionStatus.OPEN);
    }

    @Test
    void 舊路線失敗單有未結案時仍顯示異常提醒() {
        RoutesEntity route = new RoutesEntity();
        route.setId(8L);
        route.setVehicleId(3L);
        route.setStatus(RouteStatus.PUBLISHED);
        when(routesDAO.findByDateAndWarehouseId(DATE, WAREHOUSE)).thenReturn(List.of(route));
        OrdersEntity failed = order(5L, "DO-FAILED", 101L, OrderStatus.FAILED);
        when(ordersDAO.findByRouteIdAndStatusInOrderBySequence(any(), any()))
                .thenReturn(List.of(failed));
        ExceptionCasesEntity incident = new ExceptionCasesEntity();
        incident.setOrderId(5L);
        when(casesDAO.findByOrderIdInAndStatus(List.of(5L), ExceptionStatus.OPEN))
                .thenReturn(List.of(incident));

        DispatchResponse board = service.getBoard(DATE, WAREHOUSE);

        assertEquals(true, board.getRoutes().getFirst().getStops().getFirst().isOpenException());
    }

    private OrdersEntity order(Long id, String orderNumber, Long storeId, OrderStatus status) {
        OrdersEntity order = new OrdersEntity();
        order.setId(id);
        order.setOrderNumber(orderNumber);
        order.setStoreId(storeId);
        order.setBoxCount(3);
        order.setStatus(status);
        return order;
    }

    private StoresEntity store(Long id, String name) {
        StoresEntity store = new StoresEntity();
        store.setId(id);
        store.setName(name);
        return store;
    }
}
