package com.example.backend.dao;
import com.example.backend.entity.VehicleMaintenancePolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import java.math.BigDecimal;
public interface VehicleMaintenancePolicyDAO extends JpaRepository<VehicleMaintenancePolicy, BigDecimal> {}
