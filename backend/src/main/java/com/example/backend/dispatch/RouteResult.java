package com.example.backend.dispatch;

import java.util.List;

public class RouteResult {
    private List<VehicleRoute> vehicleRoutes;
    private List<Integer> droppedNodes;

    @Override
    public String toString() {
        return "RouteResult{" +
                "vehicleRoutes=" + vehicleRoutes +
                ", droppedNodes=" + droppedNodes +
                '}';
    }

    public RouteResult() {
    }

    public List<VehicleRoute> getVehicleRoutes() {
        return vehicleRoutes;
    }

    public void setVehicleRoutes(List<VehicleRoute> vehicleRoutes) {
        this.vehicleRoutes = vehicleRoutes;
    }

    public List<Integer> getDroppedNodes() {
        return droppedNodes;
    }

    public void setDroppedNodes(List<Integer> droppedNodes) {
        this.droppedNodes = droppedNodes;
    }

    public static class VehicleRoute {
        private long vehicleIndex;
        private List<Integer> nodeSequence;
        private long distance;

        @Override
        public String toString() {
            return "VehicleRoute{" +
                    "vehicleIndex=" + vehicleIndex +
                    ", nodeSequence=" + nodeSequence +
                    ", distance=" + distance +
                    '}';
        }

        public VehicleRoute() {
        }

        public long getVehicleIndex() {
            return vehicleIndex;
        }

        public void setVehicleIndex(long vehicleIndex) {
            this.vehicleIndex = vehicleIndex;
        }

        public List<Integer> getNodeSequence() {
            return nodeSequence;
        }

        public void setNodeSequence(List<Integer> nodeSequence) {
            this.nodeSequence = nodeSequence;
        }

        public long getDistance() {
            return distance;
        }

        public void setDistance(long distance) {
            this.distance = distance;
        }
    }

}
