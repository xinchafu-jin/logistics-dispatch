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
        /** 加了 steps=true 才有轉彎資料；只有起點、終點兩個點，所以固定只有一段 */
        private List<Leg> legs;

        public List<Leg> getLegs() {
            return legs;
        }

        public void setLegs(List<Leg> legs) {
            this.legs = legs;
        }

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

        /** 兩個途經點之間的一段路 */
        public static class Leg {
            private List<Step> steps;

            public List<Step> getSteps() {
                return steps;
            }

            public void setSteps(List<Step> steps) {
                this.steps = steps;
            }
        }

        /** 一步＝一個轉彎動作＋轉完之後要走的那段路 */
        public static class Step {
            /** 轉進去之後的路名；沒有路名的巷子是空字串 */
            private String name;
            /** 這一步要走的距離（公尺），也就是做完這個轉彎後，到下一個轉彎前的長度 */
            private double distance;
            private double duration;
            private Maneuver maneuver;

            public String getName() {
                return name;
            }

            public void setName(String name) {
                this.name = name;
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

            public Maneuver getManeuver() {
                return maneuver;
            }

            public void setManeuver(Maneuver maneuver) {
                this.maneuver = maneuver;
            }
        }

        /** 這一步開頭要做的動作 */
        public static class Maneuver {
            /** turn、new name、continue、merge、fork、on ramp、off ramp、end of road、roundabout、rotary、depart、arrive… */
            private String type;
            /** left、right、slight left、sharp right、straight、uturn；沒有時是 null */
            private String modifier;
            /** 轉彎點，OSRM 固定是 [經度, 緯度] */
            private double[] location;
            /** 只有圓環才有：第幾個出口；其他類型是 null，所以用 Integer */
            private Integer exit;

            public String getType() {
                return type;
            }

            public void setType(String type) {
                this.type = type;
            }

            public String getModifier() {
                return modifier;
            }

            public void setModifier(String modifier) {
                this.modifier = modifier;
            }

            public double[] getLocation() {
                return location;
            }

            public void setLocation(double[] location) {
                this.location = location;
            }

            public Integer getExit() {
                return exit;
            }

            public void setExit(Integer exit) {
                this.exit = exit;
            }
        }


    }

}
