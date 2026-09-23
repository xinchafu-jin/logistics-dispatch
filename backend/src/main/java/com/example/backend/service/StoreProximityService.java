package com.example.backend.service;

import com.example.backend.dao.GpsPingsDAO;
import com.example.backend.dao.StoresDAO;
import com.example.backend.entity.GpsPingsEntity;
import com.example.backend.entity.StoresEntity;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** 使用司機最新 GPS 確認抵達操作確實在目標門市附近。 */
@Service
@Transactional(readOnly = true)
public class StoreProximityService {

    private static final double EARTH_RADIUS_METERS = 6_371_000.0;

    private final GpsPingsDAO gpsPingsDAO;
    private final StoresDAO storesDAO;
    private final long gpsFreshnessMinutes;
    private final double allowedRadiusMeters;

    public StoreProximityService(
            GpsPingsDAO gpsPingsDAO,
            StoresDAO storesDAO,
            @Value("${app.gps.freshness-minutes:10}") long gpsFreshnessMinutes,
            @Value("${app.delivery.arrival-radius-meters:200}") double allowedRadiusMeters
    ) {
        if (gpsFreshnessMinutes <= 0) {
            throw new IllegalArgumentException("GPS有效分鐘數必須大於 0");
        }
        if (!Double.isFinite(allowedRadiusMeters) || allowedRadiusMeters <= 0) {
            throw new IllegalArgumentException("門市抵達範圍必須大於 0 公尺");
        }
        this.gpsPingsDAO = gpsPingsDAO;
        this.storesDAO = storesDAO;
        this.gpsFreshnessMinutes = gpsFreshnessMinutes;
        this.allowedRadiusMeters = allowedRadiusMeters;
    }

    public ArrivalLocation requireWithinStoreRadius(
            Long driverId,
            Long storeId,
            LocalDateTime now
    ) {
        StoresEntity store = storesDAO.findById(storeId)
                .orElseThrow(() -> new EntityNotFoundException("找不到抵達的門市，ID：" + storeId));
        GpsPingsEntity latest = gpsPingsDAO.findTopByDriverIdOrderByTimestampDesc(driverId)
                .orElseThrow(() -> new IllegalArgumentException("尚無 GPS 定位，無法確認是否抵達門市"));
        if (latest.getTimestamp() == null
                || latest.getTimestamp().isAfter(now.plusMinutes(1))
                || latest.getTimestamp().isBefore(now.minusMinutes(gpsFreshnessMinutes))) {
            throw new IllegalArgumentException(
                    "GPS 已超過 " + gpsFreshnessMinutes + " 分鐘未更新，請開啟定位後再操作");
        }
        double distanceMeters = distanceMeters(
                latest.getLat(), latest.getLng(), store.getLat(), store.getLng());
        if (distanceMeters > allowedRadiusMeters) {
            throw new IllegalArgumentException(
                    "目前距離門市約 " + Math.round(distanceMeters)
                            + " 公尺，必須進入 " + Math.round(allowedRadiusMeters)
                            + " 公尺內才能登記抵達");
        }
        return new ArrivalLocation(latest.getLat(), latest.getLng(),
                latest.getTimestamp(), distanceMeters);
    }

    private static double distanceMeters(
            Double fromLat,
            Double fromLng,
            Double toLat,
            Double toLng
    ) {
        if (fromLat == null || fromLng == null || toLat == null || toLng == null
                || !Double.isFinite(fromLat) || !Double.isFinite(fromLng)
                || !Double.isFinite(toLat) || !Double.isFinite(toLng)) {
            throw new IllegalStateException("GPS 或門市座標不完整");
        }
        double fromLatRad = Math.toRadians(fromLat);
        double toLatRad = Math.toRadians(toLat);
        double latDelta = Math.toRadians(toLat - fromLat);
        double lngDelta = Math.toRadians(toLng - fromLng);
        double a = Math.sin(latDelta / 2) * Math.sin(latDelta / 2)
                + Math.cos(fromLatRad) * Math.cos(toLatRad)
                * Math.sin(lngDelta / 2) * Math.sin(lngDelta / 2);
        double boundedA = Math.max(0, Math.min(1, a));
        return EARTH_RADIUS_METERS * 2
                * Math.atan2(Math.sqrt(boundedA), Math.sqrt(1 - boundedA));
    }

    public record ArrivalLocation(
            double lat,
            double lng,
            LocalDateTime timestamp,
            double distanceMeters
    ) {
    }
}
