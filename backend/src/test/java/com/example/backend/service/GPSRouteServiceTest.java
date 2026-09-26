package com.example.backend.service;

import com.example.backend.dispatch.OsrmClient;
import com.example.backend.dispatch.OsrmRouteResponse;
import com.example.backend.dto.request.GPSRouteDTO;
import com.example.backend.dto.respones.GPSRouteResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 導航路線：OSRM 用假的回應，驗證轉彎提示的轉換（座標順序、中文提示）。
 */
class GPSRouteServiceTest {

    private final OsrmClient osrmClient = mock(OsrmClient.class);
    private final GPSRouteService service = new GPSRouteService(osrmClient);

    @Test
    void 轉彎點座標要從經緯度反過來成緯度經度() {
        when(osrmClient.route(any(), any())).thenReturn(route(
                step("depart", null, null, "民生二路", 120.30139, 22.62732, 2),
                step("turn", "left", null, "復興一路", 120.307854, 22.627515, 1182.4),
                step("arrive", "left", null, "鐵道二街", 120.31201, 22.63915, 0)
        ));

        GPSRouteResponse response = service.findRoute(request());

        assertEquals(3, response.getSteps().size());
        GPSRouteResponse.Step turn = response.getSteps().get(1);
        // location 是 [經度, 緯度]；lat 拿到經度的話，高雄的點會變成 120 度緯度
        assertEquals(22.627515, turn.getLat());
        assertEquals(120.307854, turn.getLng());
        assertEquals("左轉進入復興一路", turn.getInstruction());
        assertEquals(1182.4, turn.getDistance());
        assertEquals("出發，沿民生二路行駛", response.getSteps().get(0).getInstruction());
        assertEquals("抵達目的地，在左側", response.getSteps().get(2).getInstruction());
    }

    @Test
    void 沒加steps參數時legs是null_回空清單不能炸() {
        OsrmRouteResponse.Route route = route();
        route.setLegs(null);
        when(osrmClient.route(any(), any())).thenReturn(route);

        GPSRouteResponse response = service.findRoute(request());

        assertTrue(response.getSteps().isEmpty());
    }

    @Test
    void 中文提示() {
        assertEquals("左轉", service.buildInstruction("turn", "left", null, ""), "沒有路名只講動作");
        assertEquals("迴轉進入民生一路", service.buildInstruction("continue", "uturn", null, "民生一路"),
                "OSRM 起點常出現 continue＋uturn，要說迴轉而不是直行");
        assertEquals("繼續沿中山路行駛", service.buildInstruction("new name", "straight", null, "中山路"));
        assertEquals("進入圓環，從第 2 個出口離開進入博愛路",
                service.buildInstruction("roundabout", "right", 2, "博愛路"));
        assertEquals("靠右行駛進入國道一號", service.buildInstruction("fork", "slight right", null, "國道一號"));
        assertEquals("靠右駛出匝道", service.buildInstruction("off ramp", "slight right", null, ""));
        assertEquals("路底右轉進入七賢路", service.buildInstruction("end of road", "right", null, "七賢路"));
        assertEquals("抵達目的地", service.buildInstruction("arrive", null, null, ""));
        assertEquals("右轉", service.buildInstruction("notification", "right", null, ""),
                "沒對應的類型退回用方向描述");
    }

    @Test
    void 非圓環的exit是null() {
        when(osrmClient.route(any(), any())).thenReturn(route(
                step("turn", "right", null, "長明街", 120.30871, 22.63816, 236)
        ));

        assertNull(service.findRoute(request()).getSteps().getFirst().getExit());
    }

    /** 座標是 Double，不給的話 findRoute 解開成 double 就 NullPointerException；值本身不重要，OSRM 是假的 */
    private GPSRouteDTO request() {
        GPSRouteDTO dto = new GPSRouteDTO();
        dto.setFromLat(22.6273);
        dto.setFromLng(120.3014);
        dto.setToLat(22.6390);
        dto.setToLng(120.3120);
        return dto;
    }

    private OsrmRouteResponse.Route route(OsrmRouteResponse.Route.Step... steps) {
        OsrmRouteResponse.Route.Geometry geometry = new OsrmRouteResponse.Route.Geometry();
        geometry.setCoordinates(new double[][]{{120.30139, 22.62732}, {120.31201, 22.63915}});
        OsrmRouteResponse.Route.Leg leg = new OsrmRouteResponse.Route.Leg();
        leg.setSteps(List.of(steps));
        OsrmRouteResponse.Route route = new OsrmRouteResponse.Route();
        route.setGeometry(geometry);
        route.setDistance(2454);
        route.setDuration(300);
        route.setLegs(List.of(leg));
        return route;
    }

    private OsrmRouteResponse.Route.Step step(String type, String modifier, Integer exit, String name,
                                               double lng, double lat, double distance) {
        OsrmRouteResponse.Route.Maneuver maneuver = new OsrmRouteResponse.Route.Maneuver();
        maneuver.setType(type);
        maneuver.setModifier(modifier);
        maneuver.setExit(exit);
        maneuver.setLocation(new double[]{lng, lat});
        OsrmRouteResponse.Route.Step step = new OsrmRouteResponse.Route.Step();
        step.setName(name);
        step.setDistance(distance);
        step.setManeuver(maneuver);
        return step;
    }
}
