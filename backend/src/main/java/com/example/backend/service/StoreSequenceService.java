package com.example.backend.service;

import com.example.backend.dao.StoresDAO;
import com.example.backend.dao.WarehousesDAO;
import com.example.backend.dispatch.OsrmClient;
import com.example.backend.dispatch.RouteOptimizer;
import com.example.backend.dispatch.RouteResult;
import com.example.backend.entity.StoresEntity;
import com.example.backend.entity.WarehousesEntity;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 替編組的一格排門市順序：從倉庫出發、跑完這些門市再回倉庫，一台車最短的順序。
 *
 * <p>跟自動排車用同一套 OSRM 距離矩陣和 RouteOptimizer，差別是這裡沒有訂單：
 * 箱數全是 0、只有一台車，而且每間門市都固定給這台車——固定的點不加 disjunction，
 * 一定要排進去，不會因為繞太遠被 OR-Tools 丟掉。只算順序，不寫資料庫。</p>
 */
@Service
public class StoreSequenceService {

    private final WarehousesDAO warehousesDAO;
    private final StoresDAO storesDAO;
    private final OsrmClient osrmClient;
    private final RouteOptimizer routeOptimizer;

    public StoreSequenceService(WarehousesDAO warehousesDAO, StoresDAO storesDAO,
                                OsrmClient osrmClient, RouteOptimizer routeOptimizer) {
        this.warehousesDAO = warehousesDAO;
        this.storesDAO = storesDAO;
        this.osrmClient = osrmClient;
        this.routeOptimizer = routeOptimizer;
    }

    public List<Long> sequence(Long warehouseId, List<Long> storeIds) {
        WarehousesEntity warehouse = warehousesDAO.findById(warehouseId)
                .orElseThrow(() -> new IllegalArgumentException("查無倉庫" + warehouseId));
        if (new HashSet<>(storeIds).size() != storeIds.size()) {
            throw new IllegalArgumentException("門市不能重複");
        }
        // 兩間以下沒有順序可排，也省一次 OSRM
        if (storeIds.size() < 2) {
            return storeIds;
        }
        Map<Long, StoresEntity> storesById = new HashMap<>();
        for (StoresEntity store : storesDAO.findAllById(storeIds)) {
            storesById.put(store.getId(), store);
        }

        // 點 0 是倉庫，點 i 是 storeIds 第 i - 1 間
        List<double[]> locations = new ArrayList<>();
        locations.add(new double[]{warehouse.getLng(), warehouse.getLat()});
        for (Long storeId : storeIds) {
            StoresEntity store = storesById.get(storeId);
            if (store == null) {
                throw new IllegalArgumentException("找不到門市，ID：" + storeId);
            }
            locations.add(new double[]{store.getLng(), store.getLat()});
        }

        long[] demands = new long[locations.size()];
        int[] allowedVehicleByNode = new int[locations.size()];
        Arrays.fill(allowedVehicleByNode, 0);
        RouteResult result = routeOptimizer.solve(
                osrmClient.table(locations), demands, new long[]{1}, 0, allowedVehicleByNode);
        if (result == null) {
            throw new IllegalStateException("排不出門市順序，請稍後再試");
        }

        // nodeSequence 頭尾都是倉庫
        List<Integer> nodes = result.getVehicleRoutes().get(0).getNodeSequence();
        List<Long> sequenced = new ArrayList<>();
        for (int i = 1; i < nodes.size() - 1; i++) {
            sequenced.add(storeIds.get(nodes.get(i) - 1));
        }
        Set<Long> missing = new HashSet<>(storeIds);
        sequenced.forEach(missing::remove);
        if (!missing.isEmpty()) {
            // 每個點都固定給這台車，照理不會漏；真的漏了寧可報錯，不要默默少一間
            throw new IllegalStateException("門市順序少了：" + missing);
        }
        return sequenced;
    }
}
