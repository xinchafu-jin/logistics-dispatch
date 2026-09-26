package com.example.backend.service;

import com.example.backend.dao.GpsPingsDAO;
import com.example.backend.dispatch.OsrmClient;
import com.example.backend.dispatch.OsrmRouteResponse;
import com.example.backend.entity.GpsPingsEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/** 以相鄰有效 GPS 點累加道路距離，不能用「距離門市減少量」取代。 */
@Service
@Transactional(readOnly = true)
public class GpsDistanceService {

    private static final double MIN_MOVEMENT_METERS = 15.0;
    private static final double MAX_REASONABLE_SPEED_KPH = 160.0;
    private static final double EARTH_RADIUS_METERS = 6_371_000.0;

    private final GpsPingsDAO gpsPingsDAO;
    private final OsrmClient osrmClient;

    public GpsDistanceService(GpsPingsDAO gpsPingsDAO, OsrmClient osrmClient) {
        this.gpsPingsDAO = gpsPingsDAO;
        this.osrmClient = osrmClient;
    }

    public DistanceResult calculate(Long driverId, LocalDateTime from, LocalDateTime to) {
        return calculate(driverId, from, to, null, null, null, null);
    }

    public DistanceResult calculate(
            Long driverId,
            LocalDateTime from,
            LocalDateTime to,
            Double startLat,
            Double startLng,
            Double endLat,
            Double endLng
    ) {
        List<GpsPingsEntity> points = gpsPingsDAO
                .findAllByDriverIdAndTimestampBetweenOrderByTimestampAsc(driverId, from, to);
        if (points.size() < 2) {
            return new DistanceResult(null, points.size(), 0, "INSUFFICIENT_GPS_POINTS");
        }

        double totalMeters = 0;
        int acceptedSegments = 0;
        if (validCoordinate(startLat, startLng)) {
            Double boundaryMeters = roadDistanceMeters(
                    startLat, startLng, points.getFirst().getLat(), points.getFirst().getLng());
            if (boundaryMeters != null) {
                totalMeters += boundaryMeters;
                acceptedSegments++;
            }
        }
        GpsPingsEntity previous = points.getFirst();
        for (int index = 1; index < points.size(); index++) {
            GpsPingsEntity current = points.get(index);
            long seconds = Duration.between(previous.getTimestamp(), current.getTimestamp()).getSeconds();
            if (seconds <= 0) {
                continue;
            }
            double straightMeters = haversineMeters(
                    previous.getLat(), previous.getLng(), current.getLat(), current.getLng());
            // 離上一個採用的點不到 15 公尺：可能是停車時 GPS 在飄，先不算這一段，但起點（previous）不能往前推。
            // 推了的話，GPS 點很密時（例如 10 秒一筆、塞車慢行）每一段都不到 15 公尺，
            // 全部被丟掉、起點一路往前移，市區里程會被算成接近 0。不推則會累積到離起點 15 公尺以上才算一段
            if (straightMeters < MIN_MOVEMENT_METERS) {
                continue;
            }
            double speedKph = straightMeters / seconds * 3.6;
            if (speedKph > MAX_REASONABLE_SPEED_KPH) {
                continue;
            }

            OsrmRouteResponse.Route road = osrmClient.route(
                    new double[]{previous.getLng(), previous.getLat()},
                    new double[]{current.getLng(), current.getLat()});
            totalMeters += road.getDistance();
            acceptedSegments++;
            previous = current;
        }
        if (validCoordinate(endLat, endLng)) {
            GpsPingsEntity last = points.getLast();
            Double boundaryMeters = roadDistanceMeters(
                    last.getLat(), last.getLng(), endLat, endLng);
            if (boundaryMeters != null) {
                totalMeters += boundaryMeters;
                acceptedSegments++;
            }
        }
        if (acceptedSegments == 0) {
            return new DistanceResult(null, points.size(), 0, "NO_ACCEPTED_GPS_SEGMENTS");
        }
        return new DistanceResult(totalMeters / 1000.0, points.size(), acceptedSegments, "COMPLETE");
    }

    private Double roadDistanceMeters(double fromLat, double fromLng, double toLat, double toLng) {
        if (haversineMeters(fromLat, fromLng, toLat, toLng) < MIN_MOVEMENT_METERS) {
            return null;
        }
        OsrmRouteResponse.Route road = osrmClient.route(
                new double[]{fromLng, fromLat},
                new double[]{toLng, toLat});
        return road.getDistance();
    }

    private boolean validCoordinate(Double lat, Double lng) {
        return lat != null && lng != null && Double.isFinite(lat) && Double.isFinite(lng)
                && lat >= -90 && lat <= 90 && lng >= -180 && lng <= 180;
    }

    private double haversineMeters(double lat1, double lng1, double lat2, double lng2) {
        double latDistance = Math.toRadians(lat2 - lat1);
        double lngDistance = Math.toRadians(lng2 - lng1);
        double a = Math.sin(latDistance / 2) * Math.sin(latDistance / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(lngDistance / 2) * Math.sin(lngDistance / 2);
        return EARTH_RADIUS_METERS * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    public static class DistanceResult {
        private final Double kilometers;
        private final int pointCount;
        private final int acceptedSegmentCount;
        private final String status;

        public DistanceResult(
                Double kilometers,
                int pointCount,
                int acceptedSegmentCount,
                String status
        ) {
            this.kilometers = kilometers;
            this.pointCount = pointCount;
            this.acceptedSegmentCount = acceptedSegmentCount;
            this.status = status;
        }

        public Double getKilometers() {
            return kilometers;
        }

        public int getPointCount() {
            return pointCount;
        }

        public int getAcceptedSegmentCount() {
            return acceptedSegmentCount;
        }

        public String getStatus() {
            return status;
        }
    }
}
