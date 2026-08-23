package com.example.backend.dispatch;

public class OsrmTableResponse {
    private String code;
    //算不出路徑時 回null 故使用類別
    private Double [][] distances;

    public OsrmTableResponse() {
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public Double[][] getDistances() {
        return distances;
    }

    public void setDistances(Double[][] distances) {
        this.distances = distances;
    }
}
