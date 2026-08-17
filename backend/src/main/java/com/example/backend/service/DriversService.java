package com.example.backend.service;

import com.example.backend.dao.DriversDAO;
import com.example.backend.dto.request.DriversDTO;
import com.example.backend.entity.DriversEntity;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional
public class DriversService {

    private final DriversDAO driversDAO;

    public DriversService(DriversDAO driversDAO) {
        this.driversDAO = driversDAO;
    }

    @Transactional(readOnly = true)
    public List<DriversDTO> findAll() {
        return driversDAO.findAll().stream().map(this::toDTO).toList();
    }

    @Transactional(readOnly = true)
    public DriversDTO findById(Long id) {
        return toDTO(findEntity(id));
    }

    public DriversDTO create(DriversDTO dto) {
        if (driversDAO.existsByAccount(dto.getAccount())) {
            throw new IllegalArgumentException("司機帳號已存在：" + dto.getAccount());
        }
        DriversEntity entity = new DriversEntity();
        apply(dto, entity);
        return toDTO(driversDAO.save(entity));
    }

    public List<DriversDTO> createAll(List<DriversDTO> dtos) {
        List<DriversEntity> entities = dtos.stream().map(dto -> {
            if (driversDAO.existsByAccount(dto.getAccount())) {
                throw new IllegalArgumentException("司機帳號已存在：" + dto.getAccount());
            }
            DriversEntity entity = new DriversEntity();
            apply(dto, entity);
            return entity;
        }).toList();
        return driversDAO.saveAll(entities).stream().map(this::toDTO).toList();
    }

    public DriversDTO update(Long id, DriversDTO dto) {
        DriversEntity entity = findEntity(id);
        if (!entity.getAccount().equals(dto.getAccount()) && driversDAO.existsByAccount(dto.getAccount())) {
            throw new IllegalArgumentException("司機帳號已存在：" + dto.getAccount());
        }
        apply(dto, entity);
        return toDTO(driversDAO.save(entity));
    }

    public void delete(Long id) {
        driversDAO.delete(findEntity(id));
    }

    private DriversEntity findEntity(Long id) {
        return driversDAO.findById(id).orElseThrow(() -> new EntityNotFoundException("找不到司機，ID：" + id));
    }

    private void apply(DriversDTO dto, DriversEntity entity) {
        entity.setAccount(dto.getAccount());
        entity.setName(dto.getName());
        entity.setPhone(dto.getPhone());
        entity.setWorkStart(dto.getWorkStart());
        entity.setWorkEnd(dto.getWorkEnd());
        entity.setRestDuration(dto.getRestDuration());
        entity.setMaxOvertimeMinutes(dto.getMaxOvertimeMinutes());
        if (dto.getIsActive() != null) {
            entity.setIsActive(dto.getIsActive());
        }
    }

    private DriversDTO toDTO(DriversEntity entity) {
        DriversDTO dto = new DriversDTO();
        dto.setId(entity.getId());
        dto.setAccount(entity.getAccount());
        dto.setName(entity.getName());
        dto.setPhone(entity.getPhone());
        dto.setWorkStart(entity.getWorkStart());
        dto.setWorkEnd(entity.getWorkEnd());
        dto.setRestDuration(entity.getRestDuration());
        dto.setMaxOvertimeMinutes(entity.getMaxOvertimeMinutes());
        dto.setIsActive(entity.getIsActive());
        return dto;
    }
}