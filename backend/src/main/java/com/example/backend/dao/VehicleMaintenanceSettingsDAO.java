package com.example.backend.dao;
import com.example.backend.entity.VehicleMaintenanceSettings;
import org.springframework.data.jpa.repository.JpaRepository;
public interface VehicleMaintenanceSettingsDAO extends JpaRepository<VehicleMaintenanceSettings, Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select s from VehicleMaintenanceSettings s where s.id = 1")
    java.util.Optional<VehicleMaintenanceSettings> findForUpdate();
}
