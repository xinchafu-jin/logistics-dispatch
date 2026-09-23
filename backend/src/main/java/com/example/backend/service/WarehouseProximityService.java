package com.example.backend.service;

import com.example.backend.dao.GpsPingsDAO;
import com.example.backend.dao.RoutesDAO;
import com.example.backend.dao.WarehousesDAO;
import com.example.backend.entity.GpsPingsEntity;
import com.example.backend.entity.RoutesEntity;
import com.example.backend.entity.WarehousesEntity;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** 以司機最新 GPS 確認車輛已回到該路線所屬倉庫附近。 */
@Service
@Transactional(readOnly = true)
public class WarehouseProximityService {

    private static final double EARTH_RADIUS_METERS = 6_371_000.0;

    private final GpsPingsDAO gpsPingsDAO;
    private final RoutesDAO routesDAO;
    private final WarehousesDAO warehousesDAO;
    private final long gpsFreshnessMinutes;
    private final double allowedRadiusMeters;

    public WarehouseProximityService(
            GpsPingsDAO gpsPingsDAO,
            RoutesDAO routesDAO,
            WarehousesDAO warehousesDAO,
            @Value("${app.gps.freshness-minutes:10}") long gpsFreshnessMinutes,
            @Value("${app.attendance.clock-out-radius-meters:200}") double allowedRadiusMeters
    ) {
        if (gpsFreshnessMinutes <= 0) {
            throw new IllegalArgumentException("GPS有效分鐘數必須大於 0");
        }
        if (!Double.isFinite(allowedRadiusMeters) || allowedRadiusMeters <= 0) {
            throw new IllegalArgumentException("下班打卡範圍必須大於 0 公尺");
        }
        this.gpsPingsDAO = gpsPingsDAO;
        this.routesDAO = routesDAO;
        this.warehousesDAO = warehousesDAO;
        this.gpsFreshnessMinutes = gpsFreshnessMinutes;
        this.allowedRadiusMeters = allowedRadiusMeters;
    }

    public double requireWithinWarehouseRadius(Long driverId, Long routeId, LocalDateTime now) {
        if (routeId == null) {
            throw new IllegalArgumentException("里程紀錄沒有綁定路線，無法確認是否已回公司");
        }
        RoutesEntity route = routesDAO.findById(routeId)
                .orElseThrow(() -> new EntityNotFoundException("找不到里程紀錄綁定的路線，ID：" + routeId));
        WarehousesEntity warehouse = warehousesDAO.findById(route.getWarehouseId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "找不到路線所屬的公司／倉庫，ID：" + route.getWarehouseId()));
        GpsPingsEntity latest = gpsPingsDAO.findTopByDriverIdOrderByTimestampDesc(driverId)
                .orElseThrow(() -> new IllegalArgumentException("尚無 GPS 定位，無法確認是否已回公司"));
        if (latest.getTimestamp() == null
                || latest.getTimestamp().isBefore(now.minusMinutes(gpsFreshnessMinutes))) {
            throw new IllegalArgumentException(
                    "GPS 已超過 " + gpsFreshnessMinutes + " 分鐘未更新，請開啟定位後再操作");
        }
        double distanceMeters = distanceMeters(
                latest.getLat(), latest.getLng(), warehouse.getLat(), warehouse.getLng());
        if (distanceMeters > allowedRadiusMeters) {
            throw new IllegalArgumentException(
                    "目前距離公司／倉庫約 " + Math.round(distanceMeters)
                            + " 公尺，必須進入 " + Math.round(allowedRadiusMeters) + " 公尺內才能操作");
        }
        return distanceMeters;
    }

    private static double distanceMeters(Double fromLat, Double fromLng, Double toLat, Double toLng) {
        if (fromLat == null || fromLng == null || toLat == null || toLng == null
                || !Double.isFinite(fromLat) || !Double.isFinite(fromLng)
                || !Double.isFinite(toLat) || !Double.isFinite(toLng)) {
            throw new IllegalStateException("GPS 或公司／倉庫座標不完整");
        }
        double fromLatRad = Math.toRadians(fromLat);
        double toLatRad = Math.toRadians(toLat);
        double latDelta = Math.toRadians(toLat - fromLat);
        double lngDelta = Math.toRadians(toLng - fromLng);
        double a = Math.sin(latDelta / 2) * Math.sin(latDelta / 2)
                + Math.cos(fromLatRad) * Math.cos(toLatRad)
                * Math.sin(lngDelta / 2) * Math.sin(lngDelta / 2);
        double boundedA = Math.max(0, Math.min(1, a));
        double c = 2 * Math.atan2(Math.sqrt(boundedA), Math.sqrt(1 - boundedA));
        return EARTH_RADIUS_METERS * c;
    }
}
