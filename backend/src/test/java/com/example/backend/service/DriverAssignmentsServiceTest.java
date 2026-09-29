package com.example.backend.service;

import com.example.backend.constants.RouteStatus;
import com.example.backend.dao.RoutesDAO;
import com.example.backend.dao.VehiclesDAO;
import com.example.backend.dao.WarehousesDAO;
import com.example.backend.dto.respones.DriverAssignmentResponse;
import com.example.backend.entity.RoutesEntity;
import com.example.backend.entity.VehiclesEntity;
import com.example.backend.entity.WarehousesEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 司機端月曆的派車資料：只查本人已發布的路線，倉庫與車輛名稱一次查齊。
 *
 * <p>司機 7 號；10/1 在倉庫 1 開車輛 11，10/2 的路線還沒排車。</p>
 */
class DriverAssignmentsServiceTest {

    private static final long DRIVER_ID = 7L;
    private static final LocalDate FROM = LocalDate.of(2026, 10, 1);
    private static final LocalDate TO = LocalDate.of(2026, 10, 31);

    private RoutesDAO routesDAO;
    private WarehousesDAO warehousesDAO;
    private VehiclesDAO vehiclesDAO;
    private DriverAssignmentsService service;

    @BeforeEach
    void setUp() {
        routesDAO = mock(RoutesDAO.class);
        warehousesDAO = mock(WarehousesDAO.class);
        vehiclesDAO = mock(VehiclesDAO.class);
        service = new DriverAssignmentsService(routesDAO, warehousesDAO, vehiclesDAO);
    }

    @Test
    void 已發布路線_帶出倉庫名稱與車牌車型_沒排車的車輛欄位是空的() {
        when(routesDAO.findByDriverIdAndStatusAndDateBetweenOrderByDateAsc(DRIVER_ID, RouteStatus.PUBLISHED, FROM, TO))
                .thenReturn(List.of(route(FROM, 1L, 11L), route(FROM.plusDays(1), 1L, null)));
        when(warehousesDAO.findAllById(Set.of(1L))).thenReturn(List.of(warehouse(1L, "桃園倉")));
        when(vehiclesDAO.findAllById(Set.of(11L))).thenReturn(List.of(vehicle(11L, "ABC-1234", "3.5 噸")));

        List<DriverAssignmentResponse> assignments = service.findPublished(DRIVER_ID, FROM, TO);

        assertEquals(2, assignments.size());
        assertEquals(FROM, assignments.get(0).getDate());
        assertEquals("桃園倉", assignments.get(0).getWarehouseName());
        assertEquals("ABC-1234", assignments.get(0).getVehiclePlateNumber());
        assertEquals("3.5 噸", assignments.get(0).getVehicleType());
        assertEquals("桃園倉", assignments.get(1).getWarehouseName());
        assertNull(assignments.get(1).getVehiclePlateNumber());
    }

    @Test
    void 只查已發布_不查草稿() {
        when(routesDAO.findByDriverIdAndStatusAndDateBetweenOrderByDateAsc(any(), any(), any(), any()))
                .thenReturn(List.of());

        service.findPublished(DRIVER_ID, FROM, TO);

        verify(routesDAO).findByDriverIdAndStatusAndDateBetweenOrderByDateAsc(DRIVER_ID, RouteStatus.PUBLISHED, FROM, TO);
    }

    @Test
    void 日期區間不合理_擋下也不查() {
        assertThrows(IllegalArgumentException.class, () -> service.findPublished(DRIVER_ID, null, TO));
        assertThrows(IllegalArgumentException.class, () -> service.findPublished(DRIVER_ID, TO, FROM));
        IllegalArgumentException tooLong = assertThrows(IllegalArgumentException.class,
                () -> service.findPublished(DRIVER_ID, FROM, FROM.plusDays(62)));

        assertEquals("一次最多查詢 62 天", tooLong.getMessage());
        verify(routesDAO, never()).findByDriverIdAndStatusAndDateBetweenOrderByDateAsc(anyLong(), any(), any(), any());
    }

    private RoutesEntity route(LocalDate date, Long warehouseId, Long vehicleId) {
        RoutesEntity route = new RoutesEntity();
        route.setDate(date);
        route.setWarehouseId(warehouseId);
        route.setVehicleId(vehicleId);
        route.setDriverId(DRIVER_ID);
        route.setStatus(RouteStatus.PUBLISHED);
        return route;
    }

    private WarehousesEntity warehouse(Long id, String name) {
        WarehousesEntity warehouse = new WarehousesEntity();
        warehouse.setId(id);
        warehouse.setName(name);
        return warehouse;
    }

    private VehiclesEntity vehicle(Long id, String plateNumber, String vehicleType) {
        VehiclesEntity vehicle = new VehiclesEntity();
        vehicle.setId(id);
        vehicle.setPlateNumber(plateNumber);
        vehicle.setVehicleType(vehicleType);
        return vehicle;
    }
}
