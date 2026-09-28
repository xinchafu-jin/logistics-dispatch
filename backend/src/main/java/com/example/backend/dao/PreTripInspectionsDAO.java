package com.example.backend.dao;

import com.example.backend.entity.PreTripInspectionsEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PreTripInspectionsDAO extends JpaRepository<PreTripInspectionsEntity, Long> {

    /**
     * 這條路線目前這組人、車、版本底下，還沒作廢的最新一筆：通過了才能出車和點交。
     * 換車、換人會讓版本 +1，舊的那筆就查不到了。
     */
    Optional<PreTripInspectionsEntity>
    findFirstByRouteIdAndDriverIdAndVehicleIdAndRouteVersionAndInvalidatedAtIsNullOrderByIdDesc(
            Long routeId, Long driverId, Long vehicleId, Integer routeVersion);

    /** 撤回發布時，把這條路線還有效的檢查全部作廢 */
    List<PreTripInspectionsEntity> findAllByRouteIdAndInvalidatedAtIsNull(Long routeId);
}
