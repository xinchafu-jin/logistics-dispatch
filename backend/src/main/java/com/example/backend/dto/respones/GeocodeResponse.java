package com.example.backend.dto.respones;

import java.util.ArrayList;
import java.util.List;

public class GeocodeResponse {

    private String query;
    private List<Result> results = new ArrayList<>();

    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query; }
    public List<Result> getResults() { return results; }
    public void setResults(List<Result> results) { this.results = results; }

    public static class Result {
        private String displayName;
        private Double lat;
        private Double lng;
        private String type;

        public Result() { }

        public Result(String displayName, Double lat, Double lng, String type) {
            this.displayName = displayName;
            this.lat = lat;
            this.lng = lng;
            this.type = type;
        }

        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }
        public Double getLat() { return lat; }
        public void setLat(Double lat) { this.lat = lat; }
        public Double getLng() { return lng; }
        public void setLng(Double lng) { this.lng = lng; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
    }
}
