package com.example.backend.dao;

import com.example.backend.entity.PreTripInspection;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface PreTripInspectionDAO extends JpaRepository<PreTripInspection, Long> {
    Optional<PreTripInspection> findFirstByRouteIdAndDriverIdAndVehicleIdAndRouteVersionAndInvalidatedAtIsNullOrderByIdDesc(
            Long routeId, Long driverId, Long vehicleId, Integer routeVersion);
    List<PreTripInspection> findByRouteIdAndInvalidatedAtIsNull(Long routeId);
}
