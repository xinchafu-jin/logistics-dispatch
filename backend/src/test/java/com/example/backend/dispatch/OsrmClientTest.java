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

}