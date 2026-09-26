package com.example.backend.dto.respones;

import java.util.List;

/**
 * 司機端導航路線。
 * <p>
 * path 的每個點是 [緯度, 經度]。OSRM 回的是 GeoJSON 順序 [經度, 緯度]，在後端轉過一次。
 * steps 是逐一轉彎的提示，座標改用具名的 lat／lng 欄位，不再用陣列，免得又要記順序。
 */
public class GPSRouteResponse {

    /** 路線座標，每個點是 [緯度, 經度] */
    private double[][] path;
    /** 總距離，公尺 */
    private double distance;
    /** 預估行駛時間，秒。依路段速限估算，不含即時路況 */
    private double duration;
    /** 轉彎提示，依行駛順序；第一步是出發（depart），最後一步是抵達（arrive） */
    private List<Step> steps;

    public GPSRouteResponse(double[][] path, double distance, double duration, List<Step> steps) {
        this.path = path;
        this.distance = distance;
        this.duration = duration;
        this.steps = steps;
    }

    public double[][] getPath() {
        return path;
    }

    public void setPath(double[][] path) {
        this.path = path;
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

    public List<Step> getSteps() {
        return steps;
    }

    public void setSteps(List<Step> steps) {
        this.steps = steps;
    }

    /**
     * 一個轉彎動作。前端拿 lat／lng 算「還有幾公尺到轉彎點」，拿 instruction 顯示或念出來。
     */
    public static class Step {
        /** OSRM 的動作類型：turn、continue、fork、roundabout、depart、arrive…；前端用來挑箭頭圖示 */
        private String type;
        /** 方向：left、right、slight left、uturn…；沒有時是 null */
        private String modifier;
        /** 轉進去之後的路名；沒有路名是空字串 */
        private String name;
        /** 中文提示，例如「左轉進入復興一路」；距離（「300 公尺後」）由前端依即時位置補上 */
        private String instruction;
        /** 轉彎點 */
        private double lat;
        private double lng;
        /** 轉彎後到下一個轉彎點的距離，公尺 */
        private double distance;
        /** 圓環第幾個出口；不是圓環是 null */
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

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getInstruction() {
            return instruction;
        }

        public void setInstruction(String instruction) {
            this.instruction = instruction;
        }

        public double getLat() {
            return lat;
        }

        public void setLat(double lat) {
            this.lat = lat;
        }

        public double getLng() {
            return lng;
        }

        public void setLng(double lng) {
            this.lng = lng;
        }

        public double getDistance() {
            return distance;
        }

        public void setDistance(double distance) {
            this.distance = distance;
        }

        public Integer getExit() {
            return exit;
        }

        public void setExit(Integer exit) {
            this.exit = exit;
        }
    }
}
