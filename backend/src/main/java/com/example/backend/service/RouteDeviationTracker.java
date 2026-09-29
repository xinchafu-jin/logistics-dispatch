package com.example.backend.service;

import com.example.backend.constants.RouteDeviationEvent;

/**
 * 一位司機的偏離狀態機：每收到一筆 GPS（算好離目前這一段的距離）就呼叫一次 onDistance。
 *
 * <ul>
 *   <li>正常時，連續 {@link RouteDeviationRules#OFF_ROUTE_STREAK} 筆超過 {@link RouteDeviationRules#OFF_ROUTE_METERS}
 *       → 變成偏離中，回傳 STARTED；中間只要有一筆沒超過，計數就重來</li>
 *   <li>偏離中不重複發：之後再偏離都回傳 NONE</li>
 *   <li>偏離中，連續 {@link RouteDeviationRules#BACK_ON_ROUTE_STREAK} 筆在 {@link RouteDeviationRules#BACK_ON_ROUTE_METERS}
 *       以內（含）→ 回到正常，回傳 RESOLVED；中間只要有一筆超過 100，回來的計數就重來</li>
 *   <li>剛好 200 不算偏離（要「超過」200）；剛好 100 算回來（「以內」含 100）</li>
 *   <li>解除之後可以再發下一次</li>
 * </ul>
 *
 * <p>為什麼要「連續」幾筆：GPS 在大樓之間常會跳一筆出去，只看單筆的話，每跳一次就吵主管一次。</p>
 *
 * <p>規格與測試：RouteDeviationTrackerTest。</p>
 */
public class RouteDeviationTracker {

    // 狀態機要記的三樣東西：目前是不是偏離中、連續偏離幾筆、偏離中連續回來幾筆
    private boolean offRoute;
    private int offRouteCount;
    private int backOnRouteCount;

    public RouteDeviationTracker() {
        this(false);
    }

    /**
     * 後端重啟後還原用：資料庫裡這位司機還有進行中的偏離（route_deviations.ended_at 是 null），
     * 就傳 true 從「偏離中」開始，之後只會等他回來、不會再開一筆重複的。
     */
    public RouteDeviationTracker(boolean offRoute) {
        this.offRoute = offRoute;
    }

    public RouteDeviationEvent onDistance(double distanceMeters) {
        if (offRoute) {
            return whileOffRoute(distanceMeters);
        }
        return whileOnRoute(distanceMeters);
    }

    /** 正常行駛中：數「連續幾筆超過 200」，夠了就變成偏離中 */
    private RouteDeviationEvent whileOnRoute(double distanceMeters) {
        // 要「超過」200 才算，剛好 200 不算，所以用 > 不用 >=
        if (distanceMeters <= RouteDeviationRules.OFF_ROUTE_METERS) {
            // 只要一筆回到路線上，前面累積的就不算連續了，從頭數
            offRouteCount = 0;
            return RouteDeviationEvent.NONE;
        }
        offRouteCount++;
        if (offRouteCount < RouteDeviationRules.OFF_ROUTE_STREAK) {
            return RouteDeviationEvent.NONE;
        }
        offRoute = true;
        resetCounts();
        return RouteDeviationEvent.STARTED;
    }

    /** 偏離中：不管多遠都不再發警報，只數「連續幾筆回到 100 以內」，夠了就回到正常 */
    private RouteDeviationEvent whileOffRoute(double distanceMeters) {
        // 「以內」含 100，所以用 >；100～200 之間也算還沒回來，而且會打斷回來的計數
        if (distanceMeters > RouteDeviationRules.BACK_ON_ROUTE_METERS) {
            backOnRouteCount = 0;
            return RouteDeviationEvent.NONE;
        }
        backOnRouteCount++;
        if (backOnRouteCount < RouteDeviationRules.BACK_ON_ROUTE_STREAK) {
            return RouteDeviationEvent.NONE;
        }
        offRoute = false;
        resetCounts();
        return RouteDeviationEvent.RESOLVED;
    }

    /** 換狀態時兩個計數都歸零，下一次偏離（或回來）要從第一筆重新數 */
    private void resetCounts() {
        offRouteCount = 0;
        backOnRouteCount = 0;
    }

    public boolean isOffRoute() {
        return offRoute;
    }
}
