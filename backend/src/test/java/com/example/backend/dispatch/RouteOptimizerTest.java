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
}