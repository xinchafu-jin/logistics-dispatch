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


    /**
     * 只要道路距離與車程：GPS 里程（每一對相鄰 GPS 點各打一次）、預估里程都用這支，一趟可能打上百次。
     * overview=false 不回路線形狀，也不算轉彎，回應小、OSRM 也少做事；回傳的 Route 沒有 geometry 與 legs。
     */
    public OsrmRouteResponse.Route route(double[] from, double[] to) {
        return fetchRoute(from, to, "overview=false");
    }

    /**
     * 發布時存預定路線用：要完整的道路形狀（畫後台地圖、比對偏離），但不需要轉彎步驟。
     * 一次同時拿到距離、車程與形狀，發布時每一段打這一支就夠，不用再另外打 route()。
     *
     * <p>一定要 overview=full：OSRM 預設的 simplified 在彎路上會直接連直線跨過去
     * （實測 2.45 公里的路完整 84 點、簡化只剩 9 點），拿來比對偏離會把照著路開的司機判成偏離。</p>
     */
    public OsrmRouteResponse.Route routeGeometry(double[] from, double[] to) {
        return fetchRoute(from, to, "overview=full&geometries=geojson");
    }

    /**
     * 司機導航用：overview＋geometries 是畫在地圖上的整條路線；steps 是逐一轉彎提示，沒加的話 legs 只會有出發、抵達兩步。
     * 比 route() 重很多，只在司機要導航時打。
     */
    public OsrmRouteResponse.Route navigationRoute(double[] from, double[] to) {
        return fetchRoute(from, to, "overview=full&geometries=geojson&steps=true");
    }

    private OsrmRouteResponse.Route fetchRoute(double[] from, double[] to, String query) {
        String coords = from[0] + "," + from[1] + ";" + to[0] + "," + to[1];
        OsrmRouteResponse osrmRouteResponse = restClient.get().
                uri("/route/v1/driving/" + coords + "?" + query).
                retrieve().body(OsrmRouteResponse.class);
        if (osrmRouteResponse == null || osrmRouteResponse.getRoutes() == null || osrmRouteResponse.getRoutes().isEmpty()) {
            throw new IllegalStateException("OSRM 沒有回傳路線，請求參數：" + query);
        }
        return osrmRouteResponse.getRoutes().getFirst();
    }

}
