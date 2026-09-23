package com.example.backend.dispatch;

import com.google.ortools.Loader;
import com.google.ortools.constraintsolver.*;
import com.google.protobuf.Duration;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Component
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
        int[] noPin = new int[distanceMatrix.length];
        Arrays.fill(noPin, -1);
        return solve(distanceMatrix, demands, vehicleCapacities, depotIndex, noPin);
    }

    /**
     * 同上，另外可以把某些點固定給某台車。
     *
     * @param allowedVehicleByNode 每個點只能由哪台車送（vehicleCapacities 的索引），-1 代表不限；
     *                             長度等於點數，倉庫那格不看
     */
    public RouteResult solve(
            long[][] distanceMatrix,
            long[] demands,
            long[] vehicleCapacities,
            int depotIndex,
            int[] allowedVehicleByNode
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
        for (int node = 1; node < distanceMatrix.length; node++) {
            long index = routingIndexManager.nodeToIndex(node);
            if (allowedVehicleByNode[node] >= 0) {
                // 調度員放進格子的單：只能由那台車送，而且不加 disjunction＝一定要送，不能被丟回待排單
                routingModel.setAllowedVehiclesForIndex(new int[]{allowedVehicleByNode[node]}, index);
            } else {
                routingModel.addDisjunction(new long[]{index}, penalty);
            }
        }
        RouteResult routeResult = new RouteResult();
        RoutingSearchParameters searchParameters = main.
                defaultRoutingSearchParameters().
                toBuilder().
                setFirstSolutionStrategy(FirstSolutionStrategy.Value.PATH_MOST_CONSTRAINED_ARC).
                setLocalSearchMetaheuristic(LocalSearchMetaheuristic.Value.GUIDED_LOCAL_SEARCH).
                setTimeLimit(Duration.newBuilder().setSeconds(5).build()).
                build();

        Assignment solution = routingModel.solveWithParameters(searchParameters);
        if (solution == null) {
            return null;
        }
        List<RouteResult.VehicleRoute> vehicleRoutes = new ArrayList<>();
        for (int i = 0; i < vehicleCapacities.length; i++) {
            List<Integer> nodeSequence = new ArrayList<>();
            long routeDistance = 0;
            long index = routingModel.start(i);
            while (!routingModel.isEnd(index)) {
                nodeSequence.add(routingIndexManager.indexToNode(index));
                long previousIndex = index;
                index = solution.value(routingModel.nextVar(index));
                routeDistance += routingModel.getArcCostForVehicle(previousIndex, index, i);
            }
            nodeSequence.add(routingIndexManager.indexToNode(index));
            RouteResult.VehicleRoute vehicleRoute = new RouteResult.VehicleRoute();
            vehicleRoute.setVehicleIndex(i);
            vehicleRoute.setNodeSequence(nodeSequence);
            vehicleRoute.setDistance(routeDistance);
            vehicleRoutes.add(vehicleRoute);
        }


        List<Integer> droppedNodes = new ArrayList<>();
        for (int i = 1; i < distanceMatrix.length; i++) {
            long index = routingIndexManager.nodeToIndex(i);
            if (solution.value(routingModel.nextVar(index)) == index) {
                droppedNodes.add(i);
            }
        }
        routeResult.setVehicleRoutes(vehicleRoutes);
        routeResult.setDroppedNodes(droppedNodes);
        return routeResult;
    }

}
