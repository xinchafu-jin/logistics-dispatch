package com.example.backend.service;

import com.example.backend.dao.GpsPingsDAO;
import com.example.backend.dispatch.OsrmClient;
import com.example.backend.dispatch.OsrmRouteResponse;
import com.example.backend.entity.GpsPingsEntity;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GPS 里程：OSRM 用假的（兩點直線距離當道路距離），驗證相鄰 GPS 點怎麼累加。
 *
 * <p>重點是 GPS 點很密（10 秒一筆慢行、或每秒一筆）時，每一對相鄰點都不到 15 公尺的過濾門檻，
 * 不能因此把里程算成 0。</p>
 */
class GpsDistanceServiceTest {

    private static final Long DRIVER_ID = 1L;
    private static final LocalDateTime START = LocalDateTime.of(2026, 9, 26, 9, 0);
    // 高雄的緯度：經度 1 度約 102,760 公尺
    private static final double LAT = 22.6273;
    private static final double METERS_PER_LNG_DEGREE = 102_760;

    private final GpsPingsDAO gpsPingsDAO = mock(GpsPingsDAO.class);
    private final OsrmClient osrmClient = mock(OsrmClient.class);
    private final GpsDistanceService service = new GpsDistanceService(gpsPingsDAO, osrmClient);

    @Test
    void 點很密_每段都不到15公尺_仍要算出里程() {
        // 每 10 秒一筆、每筆往東 11 公尺（時速約 4 公里，塞車慢行），共 61 筆＝660 公尺
        givenPings(eastwardPings(61, 11, 10));
        givenOsrmReturnsStraightDistance();

        GpsDistanceService.DistanceResult result = service.calculate(DRIVER_ID, START, START.plusHours(1));

        assertEquals("COMPLETE", result.getStatus(), "每段都不到 15 公尺，舊寫法會全部丟掉而算不出里程");
        // 累積到離上一個採用點 15 公尺以上才算一段；最後不滿 15 公尺的尾巴不算，所以略少於 660 公尺
        assertTrue(result.getKilometers() > 0.62 && result.getKilometers() < 0.67,
                "里程應接近 0.66 公里，實際：" + result.getKilometers());
    }

    @Test
    void 停車時GPS在原地飄動_不算里程() {
        List<GpsPingsEntity> pings = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            // 在同一個點附近 ±5 公尺來回飄
            double offsetMeters = (i % 2 == 0) ? 5 : -5;
            pings.add(ping(START.plusSeconds(10L * i), 120.3 + offsetMeters / METERS_PER_LNG_DEGREE));
        }
        givenPings(pings);

        GpsDistanceService.DistanceResult result = service.calculate(DRIVER_ID, START, START.plusHours(1));

        assertNull(result.getKilometers());
        assertEquals("NO_ACCEPTED_GPS_SEGMENTS", result.getStatus());
        verify(osrmClient, never()).route(any(), any());
    }

    @Test
    void 點很疏_每段都超過15公尺_照常逐段累加() {
        // 5 分鐘一筆、每筆往東 1 公里，共 3 筆＝2 公里
        givenPings(eastwardPings(3, 1_000, 300));
        givenOsrmReturnsStraightDistance();

        GpsDistanceService.DistanceResult result = service.calculate(DRIVER_ID, START, START.plusHours(1));

        assertEquals(2, result.getAcceptedSegmentCount());
        assertTrue(Math.abs(result.getKilometers() - 2.0) < 0.01, "實際：" + result.getKilometers());
    }

    private void givenPings(List<GpsPingsEntity> pings) {
        when(gpsPingsDAO.findAllByDriverIdAndTimestampBetweenOrderByTimestampAsc(anyLong(), any(), any()))
                .thenReturn(pings);
    }

    /** 假 OSRM：道路距離就是兩點直線距離（像一條筆直的路） */
    private void givenOsrmReturnsStraightDistance() {
        when(osrmClient.route(any(), any())).thenAnswer(invocation -> {
            double[] from = invocation.getArgument(0);
            double[] to = invocation.getArgument(1);
            OsrmRouteResponse.Route road = new OsrmRouteResponse.Route();
            road.setDistance(Math.abs(to[0] - from[0]) * METERS_PER_LNG_DEGREE);
            return road;
        });
    }

    private List<GpsPingsEntity> eastwardPings(int count, double stepMeters, int stepSeconds) {
        List<GpsPingsEntity> pings = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            pings.add(ping(START.plusSeconds((long) stepSeconds * i), 120.3 + i * stepMeters / METERS_PER_LNG_DEGREE));
        }
        return pings;
    }

    private GpsPingsEntity ping(LocalDateTime timestamp, double lng) {
        GpsPingsEntity ping = new GpsPingsEntity();
        ping.setDriverId(DRIVER_ID);
        ping.setLat(LAT);
        ping.setLng(lng);
        ping.setTimestamp(timestamp);
        return ping;
    }
}
