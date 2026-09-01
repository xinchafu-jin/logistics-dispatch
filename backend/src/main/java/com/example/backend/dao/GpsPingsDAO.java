package com.example.backend.dao;

import com.example.backend.entity.GpsPingsEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface GpsPingsDAO extends JpaRepository<GpsPingsEntity, Long> {

    Optional<GpsPingsEntity> findTopByDriverIdOrderByTimestampDesc(Long driverId);

    List<GpsPingsEntity> findAllByDriverIdAndTimestampBetweenOrderByTimestampAsc(
            Long driverId,
            LocalDateTime from,
            LocalDateTime to
    );

    @Query("""
            select g from GpsPingsEntity g
            where g.timestamp = (
                select max(g2.timestamp) from GpsPingsEntity g2 where g2.driverId = g.driverId
            )
            """)
    List<GpsPingsEntity> findLatestForEachDriver();

    @Query("""
            select g from GpsPingsEntity g
            where g.timestamp >= :since
              and g.timestamp = (
                select max(g2.timestamp) from GpsPingsEntity g2 where g2.driverId = g.driverId
              )
            order by g.driverId
            """)
    List<GpsPingsEntity> findLatestForEachDriverSince(@Param("since") LocalDateTime since);

    long deleteByTimestampBefore(LocalDateTime cutoff);
}
