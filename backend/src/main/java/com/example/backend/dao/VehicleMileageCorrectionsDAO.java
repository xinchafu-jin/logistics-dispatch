package com.example.backend.dao;

import com.example.backend.entity.VehicleMileageCorrectionsEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface VehicleMileageCorrectionsDAO extends JpaRepository<VehicleMileageCorrectionsEntity, Long> {

    /** 某台車的更正紀錄，新的在前 */
    List<VehicleMileageCorrectionsEntity> findAllByVehicleIdOrderByIdDesc(Long vehicleId);

    boolean existsByVehicleId(Long vehicleId);
}
