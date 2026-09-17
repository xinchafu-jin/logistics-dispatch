package com.example.backend.dto.respones;

/**
 * 司機端導航路線。
 *
 * path 的每個點是 [緯度, 經度]，也就是 Leaflet 的順序，前端可以直接餵給 L.polyline。
 * 這不是 GeoJSON —— GeoJSON 規定 [經度, 緯度]，兩者剛好相反。OSRM 回的是 GeoJSON 順序，
 * 在後端轉一次，前端就不必記這件事。
 */
public class GPSRouteResponse {

    /** 路線座標，每個點是 [緯度, 經度] */
    private double[][] path;
    /** 總距離，公尺 */
    private double distance;
    /** 預估行駛時間，秒。依路段速限估算，不含即時路況 */
    private double duration;

    public GPSRouteResponse(double[][] path, double distance, double duration) {
        this.path = path;
        this.distance = distance;
        this.duration = duration;
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
}
