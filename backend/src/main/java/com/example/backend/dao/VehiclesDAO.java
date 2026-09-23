package com.example.backend.dao;

import com.example.backend.constants.VehicleStatus;
import com.example.backend.entity.VehiclesEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface VehiclesDAO extends JpaRepository<VehiclesEntity, Long> {

    /**
     * 檢查車牌號碼是否已存在
     * 用於 Service 層新增/修改時的唯一值防呆
     */
    boolean existsByPlateNumber(String plateNumber);

    List<VehiclesEntity> findByWarehouseIdAndStatus(Long warehouseId, VehicleStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select vehicle from VehiclesEntity vehicle where vehicle.id = :vehicleId")
    Optional<VehiclesEntity> findByIdForUpdate(@Param("vehicleId") Long vehicleId);

    /** 收車時以出車讀數作為樂觀條件，原子更新車輛儀表板總里程。 */
    @Modifying
    @Query("update VehiclesEntity vehicle "
            + "set vehicle.currentOdometerKm = :endOdometer "
            + "where vehicle.id = :vehicleId "
            + "and (vehicle.currentOdometerKm = :startOdometer "
            + "or vehicle.currentOdometerKm is null)")
    int updateCurrentOdometer(
            @Param("vehicleId") Long vehicleId,
            @Param("startOdometer") int startOdometer,
            @Param("endOdometer") int endOdometer
    );

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
