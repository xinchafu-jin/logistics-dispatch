package com.example.backend.dao;
import com.example.backend.entity.VehicleMaintenanceRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
public interface VehicleMaintenanceRecordDAO extends JpaRepository<VehicleMaintenanceRecord, Long> {
    List<VehicleMaintenanceRecord> findByVehicleIdOrderByIdDesc(Long vehicleId);
    Optional<VehicleMaintenanceRecord> findByActiveVehicleId(Long vehicleId);
    boolean existsByVehicleId(Long vehicleId);
}
