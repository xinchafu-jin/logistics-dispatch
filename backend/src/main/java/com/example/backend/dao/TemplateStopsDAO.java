package com.example.backend.dao;

import com.example.backend.entity.TemplateStopsEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TemplateStopsDAO extends JpaRepository<TemplateStopsEntity, Long> {

    List<TemplateStopsEntity> findByTemplateRouteId(Long templateRouteId);

    List<TemplateStopsEntity> findAllByOrderBySequenceAsc();
}