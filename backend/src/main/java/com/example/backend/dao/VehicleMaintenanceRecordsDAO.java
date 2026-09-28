package com.example.backend.dao;

import com.example.backend.entity.VehicleMaintenanceRecordsEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface VehicleMaintenanceRecordsDAO extends JpaRepository<VehicleMaintenanceRecordsEntity, Long> {

    /** 某台車的送修歷史，新的在前 */
    List<VehicleMaintenanceRecordsEntity> findAllByVehicleIdOrderByIdDesc(Long vehicleId);

    /** 這台車進行中的那一筆（唯一鍵保證最多一筆） */
    Optional<VehicleMaintenanceRecordsEntity> findByActiveVehicleId(Long vehicleId);

    boolean existsByVehicleId(Long vehicleId);
}
