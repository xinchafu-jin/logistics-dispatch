package com.example.backend.service;

import com.example.backend.constants.AttendanceStatus;
import com.example.backend.constants.OrderStatus;
import com.example.backend.constants.RouteLegLocationType;
import com.example.backend.entity.MileageLogsEntity;
import com.example.backend.entity.OrdersEntity;
import com.example.backend.entity.RoutePlannedLegsEntity;

import java.util.List;
import java.util.Objects;

/**
 * 偏離預定路線的判斷規則。全部是純邏輯：不查資料庫、不打 OSRM，直接給資料就能測。
 * 查資料、存事件、推播交給 RouteDeviationService。
 *
 * <p>規格與測試：RouteDeviationRulesTest。</p>
 */
public final class RouteDeviationRules {

    /** 離目前這一段超過這個距離，這一筆才算偏離 */
    public static final double OFF_ROUTE_METERS = 200;
    /** 偏離中要回到這個距離內，才算回到路線；跟 200 分開，司機在邊緣時才不會一直發了又收 */
    public static final double BACK_ON_ROUTE_METERS = 100;
    /** 連續幾筆偏離才發警報（10 秒一筆，約 30 秒） */
    public static final int OFF_ROUTE_STREAK = 3;
    /** 偏離中連續幾筆回到路線才解除 */
    public static final int BACK_ON_ROUTE_STREAK = 2;
    /** 同一次偏離持續幾分鐘還沒結束，從「提示」升級成「警報」 */
    public static final int ESCALATE_AFTER_MINUTES = 10;

    /** 緯度 1 度的長度（公尺）＝地球周長 2π × 6,371 公里 ÷ 360。經度 1 度要再乘 cos(緯度)，越往北越短 */
    private static final double METERS_PER_LAT_DEGREE = 111_195;

    private RouteDeviationRules() {
    }

    /**
     * GPS 點到一段預定形狀的最短距離（公尺）。
     *
     * <p>path 是 route_planned_legs.path 解析出來的 [[經度, 緯度], ...]。
     * 要算「點到每一條線段」的距離取最小值，不是只算到頂點：直路上兩個頂點可能相隔一公里，
     * 只算頂點的話，開在正中間的司機會被算成離路線 500 公尺。投影要限制在線段範圍內（超出線段就算到端點）。</p>
     *
     * <p>做法：先把每個點換成「以司機為原點」的平面座標（公尺），再用平面幾何算點到線段的距離。
     * 一段路頂多十幾公里，這個範圍內把地球當成平的，誤差不到 1%，比 GPS 本身的誤差小很多。
     * 不用 Haversine 公式：它只能算點到點，算不了點到線段。</p>
     *
     * @throws IllegalArgumentException path 是空的
     */
    public static double distanceToPathMeters(double lat, double lng, double[][] path) {
        if (path == null || path.length == 0) {
            throw new IllegalArgumentException("預定形狀是空的，不能算距離");
        }
        // 經度 1 度多長跟緯度有關；用司機所在的緯度算一次就好，一段路南北差不到零點幾度，影響可以忽略
        double metersPerLngDegree = METERS_PER_LAT_DEGREE * Math.cos(Math.toRadians(lat));

        double[] previous = toLocalMeters(path[0], lat, lng, metersPerLngDegree);
        if (path.length == 1) {
            // 只有一個點：就是點到點的距離
            return Math.hypot(previous[0], previous[1]);
        }
        double nearest = Double.MAX_VALUE;
        for (int i = 1; i < path.length; i++) {
            double[] current = toLocalMeters(path[i], lat, lng, metersPerLngDegree);
            nearest = Math.min(nearest, distanceFromOriginToSegment(previous, current));
            previous = current;
        }
        return nearest;
    }

    /**
     * path 的一個點 [經度, 緯度] 換成以司機為原點的平面座標 {往東幾公尺, 往北幾公尺}。
     * 注意順序：path 的 [0] 是經度、[1] 是緯度，跟方法參數 (lat, lng) 剛好相反。
     */
    private static double[] toLocalMeters(double[] point, double originLat, double originLng,
                                          double metersPerLngDegree) {
        double east = (point[0] - originLng) * metersPerLngDegree;
        double north = (point[1] - originLat) * METERS_PER_LAT_DEGREE;
        return new double[]{east, north};
    }

    /**
     * 原點（司機）到線段 A→B 的最短距離。
     *
     * <p>t 是原點投影在 AB 上的位置：0＝A、1＝B、0.5＝正中間。t 要限制在 0～1 之間：
     * 超出線段就算到端點，不然開過終點的司機會落在「延長線」上，被算成距離 0。</p>
     */
    private static double distanceFromOriginToSegment(double[] a, double[] b) {
        double dx = b[0] - a[0];
        double dy = b[1] - a[1];
        double lengthSquared = dx * dx + dy * dy;
        if (lengthSquared == 0) {
            // A、B 是同一點（形狀裡偶爾有重複點）：直接算到 A，也避免下面除以 0
            return Math.hypot(a[0], a[1]);
        }
        // t = (原點 − A)·(B − A) ÷ |B − A|²；原點是 (0, 0)，所以 原點 − A 就是 (−a[0], −a[1])
        double t = (-a[0] * dx - a[1] * dy) / lengthSquared;
        t = Math.max(0, Math.min(1, t));
        double nearestEast = a[0] + t * dx;
        double nearestNorth = a[1] + t * dy;
        return Math.hypot(nearestEast, nearestNorth);
    }

    /**
     * 目前要比對的那一段；回傳 null 代表這一筆不用比對。
     *
     * <ul>
     *   <li>legs 依 sequence 排好（findAllByRouteIdOrderBySequenceAsc）；orders 是這條路線的訂單</li>
     *   <li>沒有預定形狀（legs 是空的）→ null</li>
     *   <li>有任何一張 IN_DELIVERY（已到門市、正在交貨）→ null：司機停在門市旁，本來就不在路線上</li>
     *   <li>依順序找第一段「終點門市還有未結束訂單（CONFIRMED、LOADED）」的，就是目前這一段；
     *       同一門市有好幾張單，只要還有一張沒結束就還沒送完</li>
     *   <li>COMPLETED、FAILED、NO_SIGNATURE、CANCELLED 都算結束</li>
     *   <li>每一站都結束了 → 回倉那一段（toType = WAREHOUSE）</li>
     * </ul>
     */
    public static RoutePlannedLegsEntity currentLeg(List<RoutePlannedLegsEntity> legs, List<OrdersEntity> orders) {
        if (legs.isEmpty()) {
            return null;
        }
        for (OrdersEntity order : orders) {
            if (order.getStatus() == OrderStatus.IN_DELIVERY) {
                return null;
            }
        }
        for (RoutePlannedLegsEntity leg : legs) {
            // 依序走到回倉那一段，代表前面每一站都結束了
            if (leg.getToType() == RouteLegLocationType.WAREHOUSE) {
                return leg;
            }
            if (hasUnfinishedOrder(orders, leg.getToStoreId())) {
                return leg;
            }
        }
        // 照理最後一段一定是回倉；形狀資料不完整（少了回倉那段）時就不比對，寧可漏報也不要亂報
        return null;
    }

    /**
     * 這間門市還有沒送完的單。
     * isActive() 是 CONFIRMED、LOADED、IN_DELIVERY；IN_DELIVERY 在 currentLeg 開頭已經擋掉，這裡實際只剩前兩種。
     */
    private static boolean hasUnfinishedOrder(List<OrdersEntity> orders, Long storeId) {
        for (OrdersEntity order : orders) {
            if (Objects.equals(order.getStoreId(), storeId) && order.getStatus().isActive()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 這位司機現在要不要做偏離判斷。
     *
     * <ul>
     *   <li>trip 是今天這一趟的出車紀錄；null＝還沒出車（還在倉庫點交）→ false</li>
     *   <li>已收車（endTime 有值）→ false</li>
     *   <li>出勤是休息中、已下班，或還沒打卡（null）→ false</li>
     *   <li>出車中、還沒收車，而且出勤是上班中或加班中 → true</li>
     * </ul>
     */
    public static boolean shouldCheck(MileageLogsEntity trip, AttendanceStatus attendance) {
        // 出車紀錄在出車那一刻建立、同時填 startTime（MileageLogsService），startTime 是空的只會是資料不完整，一樣不檢查
        if (trip == null || trip.getStartTime() == null || trip.getEndTime() != null) {
            return false;
        }
        return attendance == AttendanceStatus.WORKING || attendance == AttendanceStatus.OVERTIME;
    }
}
