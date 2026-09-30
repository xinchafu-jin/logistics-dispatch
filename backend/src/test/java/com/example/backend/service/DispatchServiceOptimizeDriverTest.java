package com.example.backend.service;

import com.example.backend.constants.OrderStatus;
import com.example.backend.constants.VehicleStatus;
import com.example.backend.dao.DriversDAO;
import com.example.backend.dao.OrdersDAO;
import com.example.backend.dao.RoutesDAO;
import com.example.backend.dao.StoresDAO;
import com.example.backend.dao.VehiclesDAO;
import com.example.backend.dao.WarehousesDAO;
import com.example.backend.dispatch.OsrmClient;
import com.example.backend.dispatch.RouteOptimizer;
import com.example.backend.dispatch.RouteResult;
import com.example.backend.entity.OrdersEntity;
import com.example.backend.entity.RoutesEntity;
import com.example.backend.entity.StoresEntity;
import com.example.backend.entity.VehiclesEntity;
import com.example.backend.entity.WarehousesEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 自動排車（optimize）寫進訂單的司機要跟路線一致。
 *
 * <p>看板的「司機位置」圖層靠訂單的 assigned_driver_id 找司機手上的單，
 * 自動排車以前只把司機寫在路線上、訂單留 null，後台地圖就畫不出任何司機。</p>
 *
 * <p>倉庫 1 有兩台車：KH-1001（司機 1 開）、KH-1002（沒有對應司機）。
 * OR-Tools 固定回傳「第 1 張單給 KH-1001、第 2 張單給 KH-1002」。</p>
 */
class DispatchServiceOptimizeDriverTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 1);
    private static final Long WAREHOUSE = 1L;

    private final OrdersEntity firstOrder = order(101L, 21L, 5);
    private final OrdersEntity secondOrder = order(102L, 22L, 6);
    private DispatchService service;

    @BeforeEach
    void setUp() {
        OrdersDAO ordersDAO = mock(OrdersDAO.class);
        VehiclesDAO vehiclesDAO = mock(VehiclesDAO.class);
        WarehousesDAO warehousesDAO = mock(WarehousesDAO.class);
        StoresDAO storesDAO = mock(StoresDAO.class);
        RoutesDAO routesDAO = mock(RoutesDAO.class);
        OsrmClient osrmClient = mock(OsrmClient.class);
        RouteOptimizer routeOptimizer = mock(RouteOptimizer.class);

        WarehousesEntity warehouse = new WarehousesEntity();
        warehouse.setId(WAREHOUSE);
        warehouse.setLat(22.724);
        warehouse.setLng(120.307);
        when(warehousesDAO.findById(WAREHOUSE)).thenReturn(Optional.of(warehouse));
        // 當天這一倉還沒有路線：不用清草稿，也沒有可以沿用的舊路線
        when(routesDAO.findByDateAndWarehouseId(DATE, WAREHOUSE)).thenReturn(List.of());
        when(ordersDAO.findByDeliveryDateAndStatusAndWarehouseIdAndRouteIdIsNull(DATE, OrderStatus.CONFIRMED, WAREHOUSE))
                .thenReturn(List.of(firstOrder, secondOrder));
        when(vehiclesDAO.findAllById(List.of(11L, 12L)))
                .thenReturn(List.of(vehicle(11L, "KH-1001"), vehicle(12L, "KH-1002")));
        when(storesDAO.findAllById(any())).thenReturn(List.of(store(21L), store(22L)));
        when(osrmClient.table(any())).thenReturn(new long[3][3]);
        when(routeOptimizer.solve(any(long[][].class), any(long[].class), any(long[].class), anyInt(), any(int[].class)))
                .thenReturn(result());
        when(routesDAO.save(any(RoutesEntity.class))).thenAnswer(invocation -> {
            RoutesEntity route = invocation.getArgument(0);
            route.setId(500L + route.getVehicleId());
            return route;
        });

        service = new DispatchService(ordersDAO, vehiclesDAO, warehousesDAO, storesDAO, routesDAO,
                mock(DriversDAO.class), osrmClient, routeOptimizer);
    }

    @Test
    void 自動排車_訂單跟著路線寫入司機() {
        service.optimize(DATE, WAREHOUSE, List.of(11L, 12L), Map.of(11L, 1L), Map.of());

        assertEquals(511L, firstOrder.getRouteId());
        assertEquals(11L, firstOrder.getAssignedVehicleId());
        assertEquals(1L, firstOrder.getAssignedDriverId());
    }

    @Test
    void 這台車沒有對應司機_訂單的司機是空的_不留舊值() {
        // 舊資料殘留的司機：路線沒有司機時訂單也不能指到別人
        secondOrder.setAssignedDriverId(9L);

        service.optimize(DATE, WAREHOUSE, List.of(11L, 12L), Map.of(11L, 1L), Map.of());

        assertEquals(512L, secondOrder.getRouteId());
        assertEquals(12L, secondOrder.getAssignedVehicleId());
        assertNull(secondOrder.getAssignedDriverId());
    }

    private static RouteResult result() {
        RouteResult result = new RouteResult();
        result.setVehicleRoutes(List.of(vehicleRoute(0, 1), vehicleRoute(1, 2)));
        result.setDroppedNodes(List.of());
        return result;
    }

    /** node 0 是倉庫，頭尾都是它；node 1、2 對應傳給 OR-Tools 的第 1、2 張單 */
    private static RouteResult.VehicleRoute vehicleRoute(int vehicleIndex, int orderNode) {
        RouteResult.VehicleRoute route = new RouteResult.VehicleRoute();
        route.setVehicleIndex(vehicleIndex);
        route.setNodeSequence(List.of(0, orderNode, 0));
        route.setDistance(1000);
        return route;
    }

    private static OrdersEntity order(Long id, Long storeId, int boxes) {
        OrdersEntity order = new OrdersEntity();
        order.setId(id);
        order.setOrderNumber("DO-" + id);
        order.setStoreId(storeId);
        order.setStatus(OrderStatus.CONFIRMED);
        order.setBoxCount(boxes);
        return order;
    }

    private static VehiclesEntity vehicle(Long id, String plate) {
        VehiclesEntity vehicle = new VehiclesEntity();
        vehicle.setId(id);
        vehicle.setPlateNumber(plate);
        vehicle.setWarehouseId(WAREHOUSE);
        vehicle.setStatus(VehicleStatus.AVAILABLE);
        vehicle.setCapacity(40);
        return vehicle;
    }

    private static StoresEntity store(Long id) {
        StoresEntity store = new StoresEntity();
        store.setId(id);
        store.setLat(22.70);
        store.setLng(120.30);
        return store;
    }
}
