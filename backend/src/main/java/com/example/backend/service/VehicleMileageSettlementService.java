package com.example.backend.service;

import com.example.backend.dao.VehiclesDAO;
import com.example.backend.dao.RoutesDAO;
import com.example.backend.dao.WarehousesDAO;
import com.example.backend.entity.MileageLogsEntity;
import com.example.backend.entity.RoutesEntity;
import com.example.backend.entity.WarehousesEntity;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** 將單趟 GPS 道路距離結算一次，並原子累加至車輛永久里程。 */
@Service
@Transactional
public class VehicleMileageSettlementService {

    private final GpsDistanceService gpsDistanceService;
    private final VehiclesDAO vehiclesDAO;
    private final RoutesDAO routesDAO;
    private final WarehousesDAO warehousesDAO;

    public VehicleMileageSettlementService(
            GpsDistanceService gpsDistanceService,
            VehiclesDAO vehiclesDAO,
            RoutesDAO routesDAO,
            WarehousesDAO warehousesDAO
    ) {
        this.gpsDistanceService = gpsDistanceService;
        this.vehiclesDAO = vehiclesDAO;
        this.routesDAO = routesDAO;
        this.warehousesDAO = warehousesDAO;
    }

    public void settle(MileageLogsEntity mileage, LocalDateTime settledAt) {
        if (mileage.getMileageSettledAt() != null) {
            return;
        }
        if (mileage.getVehicleId() == null || mileage.getRouteId() == null) {
            mileage.setGpsDistanceStatus("MISSING_ROUTE_OR_VEHICLE");
            return;
        }
        if (mileage.getStartTime() == null || mileage.getEndTime() == null) {
            mileage.setGpsDistanceStatus("MISSING_TRIP_BOUNDARY");
            return;
        }

        GpsDistanceService.DistanceResult result;
        try {
            RoutesEntity route = routesDAO.findById(mileage.getRouteId())
                    .orElseThrow(() -> new EntityNotFoundException(
                            "找不到里程紀錄綁定的路線，ID：" + mileage.getRouteId()));
            WarehousesEntity warehouse = warehousesDAO.findById(route.getWarehouseId())
                    .orElseThrow(() -> new EntityNotFoundException(
                            "找不到里程紀錄綁定的倉庫，ID：" + route.getWarehouseId()));
            result = gpsDistanceService.calculate(
                    mileage.getDriverId(), mileage.getStartTime(), mileage.getEndTime(),
                    warehouse.getLat(), warehouse.getLng(),
                    warehouse.getLat(), warehouse.getLng());
        } catch (RuntimeException exception) {
            mileage.setGpsDistanceStatus("GPS_DISTANCE_CALCULATION_FAILED");
            return;
        }

        mileage.setGpsDistanceStatus(result.getStatus());
        if (!"COMPLETE".equals(result.getStatus()) || result.getKilometers() == null) {
            return;
        }

        double kilometers = result.getKilometers();
        if (!Double.isFinite(kilometers) || kilometers < 0) {
            mileage.setGpsDistanceStatus("INVALID_GPS_DISTANCE");
            return;
        }
        int updated = vehiclesDAO.addCumulativeMileage(mileage.getVehicleId(), kilometers);
        if (updated != 1) {
            throw new EntityNotFoundException("找不到里程紀錄綁定的車輛，ID：" + mileage.getVehicleId());
        }
        mileage.setGpsDistanceKm(kilometers);
        mileage.setMileageSettledAt(settledAt);
    }
}
