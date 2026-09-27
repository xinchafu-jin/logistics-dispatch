package com.example.backend.service;

import com.example.backend.constants.AttendanceStatus;
import com.example.backend.constants.OrderStatus;
import com.example.backend.constants.RouteLegLocationType;
import com.example.backend.entity.MileageLogsEntity;
import com.example.backend.entity.OrdersEntity;
import com.example.backend.entity.RoutePlannedLegsEntity;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 偏離判斷的規則：距離怎麼算、比對哪一段、什麼時候要檢查。純邏輯，不需要資料庫或 OSRM。
 */
class RouteDeviationRulesTest {

    // 高雄的緯度（約 22.6273°）：緯度 1 度約 111,195 公尺、經度 1 度約 102,640 公尺
    private static final double LAT = 22.6273;
    private static final double METERS_PER_LAT_DEGREE = 111_195;
    private static final double METERS_PER_LNG_DEGREE = 102_640;

    @Nested
    class 點到預定形狀的距離 {

        // 一條往東的直路，兩個頂點相隔 1 公里：[經度, 緯度]
        private final double[][] eastRoad = {
                {120.3000, LAT},
                {120.3000 + 1_000 / METERS_PER_LNG_DEGREE, LAT},
        };

        @Test
        void 點就在頂點上_距離是0() {
            double distance = RouteDeviationRules.distanceToPathMeters(LAT, 120.3000, eastRoad);

            assertTrue(distance < 1, "實際：" + distance);
        }

        @Test
        void 點在兩個頂點正中間_要算到線段上_距離是0() {
            double middleLng = 120.3000 + 500 / METERS_PER_LNG_DEGREE;

            double distance = RouteDeviationRules.distanceToPathMeters(LAT, middleLng, eastRoad);

            assertTrue(distance < 1, "只算到頂點會得到約 500 公尺，要算到線段上；實際：" + distance);
        }

        @Test
        void 點在路的北邊250公尺_距離約250公尺() {
            double middleLng = 120.3000 + 500 / METERS_PER_LNG_DEGREE;
            double northLat = LAT + 250 / METERS_PER_LAT_DEGREE;

            double distance = RouteDeviationRules.distanceToPathMeters(northLat, middleLng, eastRoad);

            assertTrue(distance > 245 && distance < 255, "實際：" + distance);
        }

        @Test
        void 點超出路的終點_算到終點而不是延長線() {
            // 終點再往東 300 公尺，剛好在「延長線」上；投影沒限制在線段內的話會算成 0
            double beyondLng = 120.3000 + 1_300 / METERS_PER_LNG_DEGREE;

            double distance = RouteDeviationRules.distanceToPathMeters(LAT, beyondLng, eastRoad);

            assertTrue(distance > 295 && distance < 305, "實際：" + distance);
        }

        @Test
        void 形狀是空的_直接報錯() {
            assertThrows(IllegalArgumentException.class,
                    () -> RouteDeviationRules.distanceToPathMeters(LAT, 120.3, new double[0][]));
        }
    }

    @Nested
    class 目前這一段 {

        private static final long STORE_A = 11L;
        private static final long STORE_B = 22L;

        // 倉庫 → A → B → 回倉
        private final List<RoutePlannedLegsEntity> legs = List.of(
                leg(1, RouteLegLocationType.WAREHOUSE, null, RouteLegLocationType.STORE, STORE_A),
                leg(2, RouteLegLocationType.STORE, STORE_A, RouteLegLocationType.STORE, STORE_B),
                leg(3, RouteLegLocationType.STORE, STORE_B, RouteLegLocationType.WAREHOUSE, null));

        @Test
        void 還沒送任何一站_是倉庫到A那一段() {
            RoutePlannedLegsEntity current = RouteDeviationRules.currentLeg(legs,
                    List.of(order(STORE_A, OrderStatus.LOADED), order(STORE_B, OrderStatus.LOADED)));

            assertEquals(1, current.getSequence());
        }

        @Test
        void A送完了_是A到B那一段() {
            RoutePlannedLegsEntity current = RouteDeviationRules.currentLeg(legs,
                    List.of(order(STORE_A, OrderStatus.COMPLETED), order(STORE_B, OrderStatus.LOADED)));

            assertEquals(2, current.getSequence());
        }

        @Test
        void A有兩張單_只送完一張_還是倉庫到A那一段() {
            RoutePlannedLegsEntity current = RouteDeviationRules.currentLeg(legs, List.of(
                    order(STORE_A, OrderStatus.COMPLETED),
                    order(STORE_A, OrderStatus.LOADED),
                    order(STORE_B, OrderStatus.LOADED)));

            assertEquals(1, current.getSequence());
        }

        @Test
        void 失敗_無人簽收_取消都算這一站結束() {
            for (OrderStatus finished : List.of(OrderStatus.FAILED, OrderStatus.NO_SIGNATURE, OrderStatus.CANCELLED)) {
                RoutePlannedLegsEntity current = RouteDeviationRules.currentLeg(legs,
                        List.of(order(STORE_A, finished), order(STORE_B, OrderStatus.LOADED)));

                assertEquals(2, current.getSequence(), finished + " 應該算 A 已經結束");
            }
        }

        @Test
        void 每一站都結束了_是回倉那一段() {
            RoutePlannedLegsEntity current = RouteDeviationRules.currentLeg(legs,
                    List.of(order(STORE_A, OrderStatus.COMPLETED), order(STORE_B, OrderStatus.FAILED)));

            assertEquals(3, current.getSequence());
            assertEquals(RouteLegLocationType.WAREHOUSE, current.getToType());
        }

        @Test
        void 有單正在交貨_不比對() {
            // 已到門市、正在卸貨：司機停在門市旁邊，本來就不在路線上
            RoutePlannedLegsEntity current = RouteDeviationRules.currentLeg(legs,
                    List.of(order(STORE_A, OrderStatus.IN_DELIVERY), order(STORE_B, OrderStatus.LOADED)));

            assertNull(current);
        }

        @Test
        void 沒有預定形狀_不比對() {
            // 例如還沒補算的舊路線
            assertNull(RouteDeviationRules.currentLeg(List.of(), List.of(order(STORE_A, OrderStatus.LOADED))));
        }
    }

    @Nested
    class 要不要檢查 {

        @Test
        void 出車中_上班或加班_要檢查() {
            assertTrue(RouteDeviationRules.shouldCheck(trip(true, false), AttendanceStatus.WORKING));
            assertTrue(RouteDeviationRules.shouldCheck(trip(true, false), AttendanceStatus.OVERTIME));
        }

        @Test
        void 還沒出車_還在倉庫點交_不檢查() {
            assertFalse(RouteDeviationRules.shouldCheck(null, AttendanceStatus.WORKING));
        }

        @Test
        void 已經收車_不檢查() {
            assertFalse(RouteDeviationRules.shouldCheck(trip(true, true), AttendanceStatus.WORKING));
        }

        @Test
        void 休息中_下班_還沒打卡_都不檢查() {
            assertFalse(RouteDeviationRules.shouldCheck(trip(true, false), AttendanceStatus.ON_BREAK));
            assertFalse(RouteDeviationRules.shouldCheck(trip(true, false), AttendanceStatus.CLOCKED_OUT));
            assertFalse(RouteDeviationRules.shouldCheck(trip(true, false), null));
        }
    }

    private static RoutePlannedLegsEntity leg(int sequence,
                                              RouteLegLocationType fromType, Long fromStoreId,
                                              RouteLegLocationType toType, Long toStoreId) {
        RoutePlannedLegsEntity leg = new RoutePlannedLegsEntity();
        leg.setSequence(sequence);
        leg.setFromType(fromType);
        leg.setFromStoreId(fromStoreId);
        leg.setToType(toType);
        leg.setToStoreId(toStoreId);
        return leg;
    }

    private static OrdersEntity order(long storeId, OrderStatus status) {
        OrdersEntity order = new OrdersEntity();
        order.setStoreId(storeId);
        order.setStatus(status);
        return order;
    }

    private static MileageLogsEntity trip(boolean started, boolean ended) {
        MileageLogsEntity trip = new MileageLogsEntity();
        if (started) {
            trip.setStartTime(LocalDateTime.of(2026, 9, 27, 8, 30));
        }
        if (ended) {
            trip.setEndTime(LocalDateTime.of(2026, 9, 27, 17, 0));
        }
        return trip;
    }
}
