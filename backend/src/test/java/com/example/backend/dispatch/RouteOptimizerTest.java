package com.example.backend.dispatch;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RouteOptimizerTest {
    @Test
    void test() {
        long[][] distanceMatric = {
                {0, 5000, 8000, 6000},   // 倉庫 → 各點
                {5000, 0, 4000, 7000},   // 東區 → 各點
                {8000, 4000, 0, 3000},   // 永康 → 各點
                {6000, 7000, 3000, 0}
        };
        long[] demands = {0, 3, 4, 3};
        long[] capacities = {10, 10};         // 兩台車，各裝 10 箱
        int depotIndex = 0;
        RouteOptimizer routeOptimizer = new RouteOptimizer();
        RouteResult result = routeOptimizer.solve(distanceMatric, demands, capacities, depotIndex);
        System.out.println(result);
    }

    /**
     * 三個點一台車就裝得下，不固定的話 OR-Tools 會全部交給同一台車。
     * 把永康（點 2）固定給車 1，它就一定要出現在車 1 的路線裡。
     */
    @Test
    void 固定的點只能排在指定的車上() {
        long[][] distance = {
                {0, 5000, 8000, 6000},
                {5000, 0, 4000, 7000},
                {8000, 4000, 0, 3000},
                {6000, 7000, 3000, 0}
        };
        long[] demands = {0, 3, 4, 3};
        long[] capacities = {10, 10};

        RouteResult result = new RouteOptimizer().solve(distance, demands, capacities, 0, new int[]{-1, -1, 1, -1});

        assertTrue(result.getVehicleRoutes().get(1).getNodeSequence().contains(2),
                "永康應該在車 1：" + result.getVehicleRoutes());
        assertFalse(result.getVehicleRoutes().get(0).getNodeSequence().contains(2));
        assertTrue(result.getDroppedNodes().isEmpty());
    }

    /**
     * 點 1 遠到來回的距離比丟掉的罰金（100,000）還貴，不固定時 OR-Tools 會寧可丟掉它；
     * 固定之後不能丟，一定要送。
     */
    @Test
    void 固定的點不會被丟回待排單() {
        long[][] distance = {
                {0, 1_000_000, 5000},
                {1_000_000, 0, 1_000_000},
                {5000, 1_000_000, 0}
        };
        long[] demands = {0, 2, 2};
        long[] capacities = {10};
        RouteOptimizer optimizer = new RouteOptimizer();

        RouteResult free = optimizer.solve(distance, demands, capacities, 0, new int[]{-1, -1, -1});
        assertTrue(free.getDroppedNodes().contains(1), "不固定時遠的點應該被丟掉，否則這個測試證明不了什麼");

        RouteResult pinned = optimizer.solve(distance, demands, capacities, 0, new int[]{-1, 0, -1});
        assertFalse(pinned.getDroppedNodes().contains(1));
        assertTrue(pinned.getVehicleRoutes().get(0).getNodeSequence().contains(1));
    }
}
