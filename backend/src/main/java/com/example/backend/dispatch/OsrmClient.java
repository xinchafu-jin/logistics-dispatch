package com.example.backend.dispatch;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class OsrmClient {
    private final RestClient restClient;

    //請求交易
    public OsrmClient(@Value("${app.osrm.base-url}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    public long[][] table(List<double[]> locations) {
        //把座標陣列重組成座標字串 a,b ; x,y 格式
        String coords = locations.stream().map(item -> item[0] + "," + item[1]).
                collect(Collectors.joining(";"));
//餵給 OR-Tools 算最佳順序
        OsrmTableResponse tableResponse = restClient.get().
                uri("/table/v1/driving/" + coords + "?annotations=distance").
                retrieve().
                body(OsrmTableResponse.class);
        if (tableResponse == null || tableResponse.getDistances() == null) {
            throw new IllegalStateException("OSRM 沒有回傳距離矩陣，請確認 annotations=distance 參數");

        }
        int n = tableResponse.getDistances().length;
        long[][] matrix = new long[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                Double d = tableResponse.getDistances()[i][j];
                if (d == null) {
                    throw new IllegalStateException("OSRM 無法計算第 " + i + " 點到第 " + j + " 點的路徑，"
                            + "起點 " + locations.get(i)[0] + "," + locations.get(i)[1]
                            + " 終點 " + locations.get(j)[0] + "," + locations.get(j)[1]);
                }
                matrix[i][j] = Math.round(d);


            }

        }
        return matrix;
    }


    public OsrmRouteResponse.Route route(double[] from, double[] to) {
        String coords = from[0] + "," + from[1] + ";" + to[0] + "," + to[1];
        //導航：畫線給司機看
        OsrmRouteResponse osrmRouteResponse = restClient.get().
                uri("/route/v1/driving/" + coords + "?overview=full&geometries=geojson").
                retrieve().body(OsrmRouteResponse.class);
        if (osrmRouteResponse == null || osrmRouteResponse.getRoutes() == null || osrmRouteResponse.getRoutes().isEmpty()) {
            throw new IllegalStateException("OSRM 沒有回傳，請確認 geometries=geojson 參數");
        }
        return osrmRouteResponse.getRoutes().getFirst();
    }

}
