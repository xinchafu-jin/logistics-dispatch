package com.example.backend.dao;

import com.example.backend.entity.VehicleMaintenanceSettingsEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface VehicleMaintenanceSettingsDAO extends JpaRepository<VehicleMaintenanceSettingsEntity, Long> {
}
