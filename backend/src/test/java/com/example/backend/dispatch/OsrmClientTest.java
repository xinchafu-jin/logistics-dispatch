package com.example.backend.dispatch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OsrmClientTest {
    // 連的是本機 docker 跑的 OSRM（全台路網好幾 GB），CI 上沒有；GitHub Actions 會自動設 CI=true
    @Test
    @DisabledIfEnvironmentVariable(named = "CI", matches = "true")
    void table() {
        OsrmClient osrmClient = new OsrmClient("http://localhost:5001");
        // 台北車站 / 台北101 / 板橋車站，注意是 {經度, 緯度}
        List<double[]> locations = List.of(
                new double[]{121.5170, 25.0478},
                new double[]{121.5654, 25.0330},
                new double[]{121.4628, 25.0139}
        );

        long[][] matrix = osrmClient.table(locations);

        for (long[] row : matrix) {
            System.out.println(Arrays.toString(row));
        }
    }

    // 同樣連本機 OSRM：確認網址參數（steps=true）和 legs／steps／maneuver 欄位名稱真的接得到資料。
    // 參數打錯 OSRM 回 400；欄位名稱打錯 Jackson 不會報錯、只會是 null，這兩種都要靠真的打一次才抓得到
    @Test
    @DisabledIfEnvironmentVariable(named = "CI", matches = "true")
    void routeWithSteps() {
        OsrmClient osrmClient = new OsrmClient("http://localhost:5001");

        // 高雄民生二路 → 鐵道二街，注意是 {經度, 緯度}
        OsrmRouteResponse.Route route = osrmClient.route(
                new double[]{120.3014, 22.6273}, new double[]{120.3120, 22.6390});

        assertNotNull(route.getLegs(), "沒有 legs：Route 少了 legs 欄位或 setter");
        List<OsrmRouteResponse.Route.Step> steps = route.getLegs().getFirst().getSteps();
        assertTrue(steps.size() > 2, "只有出發、抵達兩步：網址沒帶 steps=true");
        assertEquals("depart", steps.getFirst().getManeuver().getType());
        assertEquals("arrive", steps.getLast().getManeuver().getType());
        assertEquals(2, steps.get(1).getManeuver().getLocation().length);
    }

}
