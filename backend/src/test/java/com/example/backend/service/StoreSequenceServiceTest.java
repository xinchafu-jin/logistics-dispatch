package com.example.backend.service;

import com.example.backend.dao.StoresDAO;
import com.example.backend.dao.WarehousesDAO;
import com.example.backend.dispatch.OsrmClient;
import com.example.backend.dispatch.RouteOptimizer;
import com.example.backend.entity.StoresEntity;
import com.example.backend.entity.WarehousesEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 排門市順序：OSRM 用假的距離矩陣，OR-Tools 用真的，驗證排出來的是最順的跑法。
 *
 * <p>矩陣的點 0 是倉庫，點 i 是送進去的第 i 間門市（跟 StoreSequenceService 組 locations 的順序一致）。</p>
 */
class StoreSequenceServiceTest {

    private static final Long WAREHOUSE = 1L;

    private final OsrmClient osrmClient = mock(OsrmClient.class);
    private StoreSequenceService service;

    @BeforeEach
    void setUp() {
        WarehousesDAO warehousesDAO = mock(WarehousesDAO.class);
        StoresDAO storesDAO = mock(StoresDAO.class);
        WarehousesEntity warehouse = new WarehousesEntity();
        warehouse.setId(WAREHOUSE);
        warehouse.setLat(22.99);
        warehouse.setLng(120.21);
        when(warehousesDAO.findById(WAREHOUSE)).thenReturn(Optional.of(warehouse));
        when(storesDAO.findAllById(any())).thenReturn(List.of(store(11L), store(12L), store(13L)));
        service = new StoreSequenceService(warehousesDAO, storesDAO, osrmClient, new RouteOptimizer());
    }

    /**
     * 三間門市在同一條路上：13 離倉庫 1 公里、12 是 2 公里、11 是 3 公里。
     * 照送進來的 11 → 13 → 12 跑會來回折返（3+2+1+2＝8 公里），最順是沿路一路送（6 公里），
     * 往前或往回走距離一樣，兩個方向都算對。
     */
    @Test
    void 照距離排出一路送的順序() {
        when(osrmClient.table(any())).thenReturn(new long[][]{
                // 倉庫, 11,   13,   12
                {0, 3000, 1000, 2000},
                {3000, 0, 2000, 1000},
                {1000, 2000, 0, 1000},
                {2000, 1000, 1000, 0},
        });

        List<Long> result = service.sequence(WAREHOUSE, List.of(11L, 13L, 12L));

        assertTrue(result.equals(List.of(13L, 12L, 11L)) || result.equals(List.of(11L, 12L, 13L)),
                "應該沿路一路送，實際：" + result);
    }

    /** 一間門市繞到 200 公里外，比 RouteOptimizer 丟點的罰金還貴，也不能被丟掉 */
    @Test
    void 很遠的門市也不會被丟掉() {
        when(osrmClient.table(any())).thenReturn(new long[][]{
                {0, 1000, 200_000},
                {1000, 0, 200_000},
                {200_000, 200_000, 0},
        });

        List<Long> result = service.sequence(WAREHOUSE, List.of(11L, 12L));

        assertEquals(2, result.size(), "兩間都要在：" + result);
        assertTrue(result.containsAll(List.of(11L, 12L)));
    }

    @Test
    void 只有一間_原樣回傳不查OSRM() {
        assertEquals(List.of(11L), service.sequence(WAREHOUSE, List.of(11L)));
        verify(osrmClient, never()).table(any());
    }

    @Test
    void 門市重複_擋下() {
        assertThrows(IllegalArgumentException.class, () -> service.sequence(WAREHOUSE, List.of(11L, 11L)));
    }

    private StoresEntity store(Long id) {
        StoresEntity store = new StoresEntity();
        store.setId(id);
        store.setLat(23.0);
        store.setLng(120.2);
        return store;
    }
}
