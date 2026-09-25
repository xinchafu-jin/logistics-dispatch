package com.example.backend.dao;

import com.example.backend.entity.DriverLeaveRequestEventsEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DriverLeaveRequestEventsDAO extends JpaRepository<DriverLeaveRequestEventsEntity, Long> {
    List<DriverLeaveRequestEventsEntity> findByLeaveRequestIdOrderByOccurredAtAscIdAsc(Long leaveRequestId);
}
