package com.example.backend.service;

import com.example.backend.dispatch.OsrmClient;
import com.example.backend.dispatch.OsrmRouteResponse;
import com.example.backend.dto.request.GPSRouteDTO;
import com.example.backend.dto.respones.GPSRouteResponse;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class GPSRouteService {
    private final OsrmClient osrmClient;

    public GPSRouteService(OsrmClient osrmClient) {
        this.osrmClient = osrmClient;
    }

    public GPSRouteResponse findRoute(GPSRouteDTO gpsRouteDTO) {
        double[] from = {gpsRouteDTO.getFromLng(), gpsRouteDTO.getFromLat()};
        double[] to = {gpsRouteDTO.getToLng(), gpsRouteDTO.getToLat()};
        OsrmRouteResponse.Route route = osrmClient.route(from, to);

        double[][] coordinates = route.getGeometry().getCoordinates();
        double[][] path = new double[coordinates.length][2];
        for (int i = 0; i < coordinates.length; i++) {
            path[i][0] = coordinates[i][1];
            path[i][1] = coordinates[i][0];
        }
        return new GPSRouteResponse(path, route.getDistance(), route.getDuration(), toSteps(route));
    }

    /**
     * OSRM 的 legs → steps 攤平成一串轉彎提示。
     * 起點、終點只有兩個點，所以只會有一段 leg；保險起見還是全部接起來，不只取第一段。
     */
    private List<GPSRouteResponse.Step> toSteps(OsrmRouteResponse.Route route) {
        List<GPSRouteResponse.Step> steps = new ArrayList<>();
        if (route.getLegs() == null) {
            return steps;
        }
        for (OsrmRouteResponse.Route.Leg leg : route.getLegs()) {
            if (leg.getSteps() == null) {
                continue;
            }
            for (OsrmRouteResponse.Route.Step osrmStep : leg.getSteps()) {
                steps.add(toStep(osrmStep));
            }
        }
        return steps;
    }

    private GPSRouteResponse.Step toStep(OsrmRouteResponse.Route.Step osrmStep) {
        OsrmRouteResponse.Route.Maneuver maneuver = osrmStep.getManeuver();
        String name = osrmStep.getName() == null ? "" : osrmStep.getName();

        GPSRouteResponse.Step step = new GPSRouteResponse.Step();
        step.setType(maneuver.getType());
        step.setModifier(maneuver.getModifier());
        step.setName(name);
        step.setExit(maneuver.getExit());
        step.setDistance(osrmStep.getDistance());
        // OSRM 的 location 是 [經度, 緯度]，跟 path 一樣要反過來
        step.setLat(maneuver.getLocation()[1]);
        step.setLng(maneuver.getLocation()[0]);
        step.setInstruction(buildInstruction(maneuver.getType(), maneuver.getModifier(), maneuver.getExit(), name));
        return step;
    }

    /**
     * 組中文提示，例如「左轉進入復興一路」。只描述「要做什麼」，距離（「300 公尺後」）由前端依即時位置補。
     *
     * <p>type 清單見 OSRM 文件 StepManeuver；沒列到的類型（例如 notification）退回用 modifier 描述方向。</p>
     */
    String buildInstruction(String type, String modifier, Integer exit, String name) {
        String onto = name.isBlank() ? "" : "進入" + name;
        if (type == null) {
            return withRoad(directionText(modifier), onto);
        }
        return switch (type) {
            case "depart" -> name.isBlank() ? "出發" : "出發，沿" + name + "行駛";
            case "arrive" -> arriveText(modifier);
            case "roundabout", "rotary" -> exit == null
                    ? withRoad("進入圓環", onto)
                    : withRoad("進入圓環，從第 " + exit + " 個出口離開", onto);
            case "exit roundabout", "exit rotary" -> withRoad("離開圓環", onto);
            case "on ramp" -> withRoad("駛入匝道", onto);
            case "off ramp" -> withRoad(sideText(modifier) + "駛出匝道", onto);
            case "fork" -> withRoad(sideText(modifier) + "行駛", onto);
            case "merge" -> name.isBlank() ? "匯入車道" : "匯入" + name;
            case "end of road" -> withRoad("路底" + directionText(modifier), onto);
            // new name：路名換了但不用轉彎；continue：直走或順著彎道
            case "new name", "continue" -> "straight".equals(modifier) || modifier == null
                    ? (name.isBlank() ? "直行" : "繼續沿" + name + "行駛")
                    : withRoad(directionText(modifier), onto);
            default -> withRoad(directionText(modifier), onto);
        };
    }

    /** 轉彎方向的中文；uturn 也算在這裡，所以 continue／turn 遇到迴轉都會說「迴轉」 */
    private String directionText(String modifier) {
        if (modifier == null) {
            return "直行";
        }
        return switch (modifier) {
            case "left" -> "左轉";
            case "right" -> "右轉";
            case "slight left" -> "靠左";
            case "slight right" -> "靠右";
            case "sharp left" -> "大角度左轉";
            case "sharp right" -> "大角度右轉";
            case "uturn" -> "迴轉";
            default -> "直行";
        };
    }

    /** 岔路、匝道只分左右，用「靠左／靠右」比「左轉」貼切 */
    private String sideText(String modifier) {
        if (modifier == null) {
            return "";
        }
        return modifier.contains("left") ? "靠左" : modifier.contains("right") ? "靠右" : "";
    }

    private String arriveText(String modifier) {
        if (modifier != null && modifier.contains("left")) {
            return "抵達目的地，在左側";
        }
        if (modifier != null && modifier.contains("right")) {
            return "抵達目的地，在右側";
        }
        return "抵達目的地";
    }

    /** 動作後面接「進入某某路」；沒有路名就只講動作 */
    private String withRoad(String action, String onto) {
        return onto.isEmpty() ? action : action + onto;
    }
}
