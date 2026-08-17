package com.example.backend.dispatch;

import com.google.ortools.Loader;
import com.google.ortools.constraintsolver.RoutingIndexManager;
import com.google.ortools.constraintsolver.RoutingModel;

public class RouteOptimizer {
    static {
        Loader.loadNativeLibraries();
    }

    public RouteResult solve(
            long[][] distanceMatrix,// 距離矩陣
            long[] demands, //訂單
            long[] vehicleCapacities, // 車容量
            int depotIndex// 倉庫
    ) {
        RoutingIndexManager routingIndexManager = new RoutingIndexManager(
                distanceMatrix.length,
                vehicleCapacities.length,
                depotIndex
        );
        RoutingModel routingModel = new RoutingModel(routingIndexManager);
        int transitCallbackIndex = routingModel.registerTransitCallback((fromIndex, toIndex) -> {
            int fromNode = routingIndexManager.indexToNode(fromIndex);
            int toNode = routingIndexManager.indexToNode(toIndex);
            return distanceMatrix[fromNode][toNode];
        });
        routingModel.setArcCostEvaluatorOfAllVehicles(transitCallbackIndex);
        // 容量計算
        int demandCallbackIndex = routingModel.registerUnaryTransitCallback((index) -> {
            int node = routingIndexManager.indexToNode(index);
            return demands[node];
        });
        routingModel.addDimensionWithVehicleCapacity(
                demandCallbackIndex,
                0,
                vehicleCapacities,
                true,
                "Capacity"
        );
// 容量不夠跳過
        long penalty = 100_000;
        for (int node = 1 ;node < distanceMatrix.length ; node++  ) {
            routingModel.addDisjunction(new long []{routingIndexManager.nodeToIndex(node)},penalty);
        }

        return null;
    }

}
