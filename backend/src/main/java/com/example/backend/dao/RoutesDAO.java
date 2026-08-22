package com.example.backend.dao;

import com.example.backend.entity.RoutesEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RoutesDAO extends JpaRepository<RoutesEntity, Long> {
}
