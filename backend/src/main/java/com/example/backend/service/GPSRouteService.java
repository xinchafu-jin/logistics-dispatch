package com.example.backend.service;

import com.example.backend.dispatch.OsrmClient;
import com.example.backend.dispatch.OsrmRouteResponse;
import com.example.backend.dto.request.GPSRouteDTO;
import com.example.backend.dto.respones.GPSRouteResponse;
import org.springframework.stereotype.Service;

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
        return new GPSRouteResponse(path, route.getDistance(), route.getDuration());
    }

}
