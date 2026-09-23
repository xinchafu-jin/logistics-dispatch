package com.example.backend.dao;

import com.example.backend.constants.VehicleStatus;
import com.example.backend.entity.VehiclesEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface VehiclesDAO extends JpaRepository<VehiclesEntity, Long> {

    /**
     * 檢查車牌號碼是否已存在
     * 用於 Service 層新增/修改時的唯一值防呆
     */
    boolean existsByPlateNumber(String plateNumber);

    List<VehiclesEntity> findByWarehouseIdAndStatus(Long warehouseId, VehicleStatus status);

    /** 原子累加車輛永久里程，避免兩次結算互相覆蓋。 */
    @Modifying
    @Query("update VehiclesEntity vehicle "
            + "set vehicle.cumulativeMileageKm = "
            + "coalesce(vehicle.cumulativeMileageKm, 0) + :kilometers "
            + "where vehicle.id = :vehicleId")
    int addCumulativeMileage(
            @Param("vehicleId") Long vehicleId,
            @Param("kilometers") double kilometers
    );
}
