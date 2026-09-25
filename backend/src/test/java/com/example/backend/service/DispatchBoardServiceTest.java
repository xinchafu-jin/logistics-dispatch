package com.example.backend.service;

import com.example.backend.constants.OrderStatus;
import com.example.backend.dao.DriversDAO;
import com.example.backend.dao.OrdersDAO;
import com.example.backend.dao.RoutesDAO;
import com.example.backend.dao.StoresDAO;
import com.example.backend.dao.VehiclesDAO;
import com.example.backend.dao.WarehousesDAO;
import com.example.backend.dto.respones.DispatchResponse;
import com.example.backend.entity.OrdersEntity;
import com.example.backend.entity.StoresEntity;
import com.example.backend.entity.WarehousesEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
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
    private DispatchBoardService service;

    @BeforeEach
    void setUp() {
        OrdersDAO ordersDAO = mock(OrdersDAO.class);
        RoutesDAO routesDAO = mock(RoutesDAO.class);
        storesDAO = mock(StoresDAO.class);
        VehiclesDAO vehiclesDAO = mock(VehiclesDAO.class);
        DriversDAO driversDAO = mock(DriversDAO.class);
        WarehousesDAO warehousesDAO = mock(WarehousesDAO.class);

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

        service = new DispatchBoardService(
                ordersDAO, routesDAO, storesDAO, vehiclesDAO, driversDAO, warehousesDAO);
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
