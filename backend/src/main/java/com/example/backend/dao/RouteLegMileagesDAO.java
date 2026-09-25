package com.example.backend.dao;

import com.example.backend.entity.RouteLegMileagesEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface RouteLegMileagesDAO extends JpaRepository<RouteLegMileagesEntity, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<RouteLegMileagesEntity> findFirstByMileageLogIdOrderBySequenceDesc(Long mileageLogId);

    List<RouteLegMileagesEntity> findAllByRouteIdInOrderByRouteIdAscSequenceAsc(Collection<Long> routeIds);
}
