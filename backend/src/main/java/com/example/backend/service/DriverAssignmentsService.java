package com.example.backend.service;

import com.example.backend.constants.RouteStatus;
import com.example.backend.dao.RoutesDAO;
import com.example.backend.dao.VehiclesDAO;
import com.example.backend.dao.WarehousesDAO;
import com.example.backend.dto.respones.DriverAssignmentResponse;
import com.example.backend.entity.RoutesEntity;
import com.example.backend.entity.VehiclesEntity;
import com.example.backend.entity.WarehousesEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 司機端月曆點某一天時，顯示那天在哪個倉庫上班、開哪台車。
 *
 * <p>只看已發布（PUBLISHED）的路線：草稿還會被主管在看板上拖來拖去，
 * 司機看到草稿的車號，隔天可能就換了。沒有已發布路線的日子不回，前端改顯示所屬倉庫與「尚未派車」。</p>
 */
@Service
@Transactional(readOnly = true)
public class DriverAssignmentsService {

    /** 月曆一次載一個月，留點餘裕；擋住一次查好幾年的請求 */
    static final int MAX_RANGE_DAYS = 62;

    private final RoutesDAO routesDAO;
    private final WarehousesDAO warehousesDAO;
    private final VehiclesDAO vehiclesDAO;

    public DriverAssignmentsService(RoutesDAO routesDAO, WarehousesDAO warehousesDAO, VehiclesDAO vehiclesDAO) {
        this.routesDAO = routesDAO;
        this.warehousesDAO = warehousesDAO;
        this.vehiclesDAO = vehiclesDAO;
    }

    /** driverId 由 Controller 從 JWT 取，只查得到自己的路線 */
    public List<DriverAssignmentResponse> findPublished(Long driverId, LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw new IllegalArgumentException("查詢起訖日期不能為空");
        }
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("查詢起始日期不能晚於結束日期");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_RANGE_DAYS) {
            throw new IllegalArgumentException("一次最多查詢 " + MAX_RANGE_DAYS + " 天");
        }

        List<RoutesEntity> routes = routesDAO.findByDriverIdAndStatusAndDateBetweenOrderByDateAsc(
                driverId, RouteStatus.PUBLISHED, from, to);

        // 倉庫、車輛各查一次再對回去，不要每條路線各查一次（N+1）
        Set<Long> warehouseIds = new HashSet<>();
        Set<Long> vehicleIds = new HashSet<>();
        for (RoutesEntity route : routes) {
            if (route.getWarehouseId() != null) {
                warehouseIds.add(route.getWarehouseId());
            }
            if (route.getVehicleId() != null) {
                vehicleIds.add(route.getVehicleId());
            }
        }
        Map<Long, WarehousesEntity> warehouses = new HashMap<>();
        for (WarehousesEntity warehouse : warehousesDAO.findAllById(warehouseIds)) {
            warehouses.put(warehouse.getId(), warehouse);
        }
        Map<Long, VehiclesEntity> vehicles = new HashMap<>();
        for (VehiclesEntity vehicle : vehiclesDAO.findAllById(vehicleIds)) {
            vehicles.put(vehicle.getId(), vehicle);
        }

        List<DriverAssignmentResponse> responses = new ArrayList<>();
        for (RoutesEntity route : routes) {
            WarehousesEntity warehouse = warehouses.get(route.getWarehouseId());
            VehiclesEntity vehicle = vehicles.get(route.getVehicleId());
            DriverAssignmentResponse response = new DriverAssignmentResponse();
            response.setDate(route.getDate());
            response.setWarehouseId(route.getWarehouseId());
            response.setWarehouseName(warehouse == null ? null : warehouse.getName());
            response.setVehiclePlateNumber(vehicle == null ? null : vehicle.getPlateNumber());
            response.setVehicleType(vehicle == null ? null : vehicle.getVehicleType());
            responses.add(response);
        }
        return responses;
    }
}
