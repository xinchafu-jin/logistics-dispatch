package com.example.backend.dao;

import com.example.backend.entity.DispatchTemplatesEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DispatchTemplatesDAO extends JpaRepository<DispatchTemplatesEntity, Long> {

    boolean existsByName(String name);
}
