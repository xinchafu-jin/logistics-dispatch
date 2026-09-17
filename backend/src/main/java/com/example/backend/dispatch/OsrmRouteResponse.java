package com.example.backend.dispatch;

import java.util.List;

public class OsrmRouteResponse {
    private String code;
    private List<Route> routes;

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public List<Route> getRoutes() {
        return routes;
    }

    public void setRoutes(List<Route> routes) {
        this.routes = routes;
    }

    public static class Route {
        private double distance;
        private double duration;
        private Geometry geometry;

        public Geometry getGeometry() {
            return geometry;
        }

        public void setGeometry(Geometry geometry) {
            this.geometry = geometry;
        }

        public double getDistance() {
            return distance;
        }

        public void setDistance(double distance) {
            this.distance = distance;
        }

        public double getDuration() {
            return duration;
        }

        public void setDuration(double duration) {
            this.duration = duration;
        }

        public static class Geometry {
            private double[][] coordinates;

            public double[][] getCoordinates() {
                return coordinates;
            }

            public void setCoordinates(double[][] coordinates) {
                this.coordinates = coordinates;
            }
        }


    }

}
