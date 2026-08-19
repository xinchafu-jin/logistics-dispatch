package com.example.backend.service;

import com.example.backend.dao.WarehousesDAO;
import com.example.backend.dto.request.WarehousesDTO;
import com.example.backend.entity.WarehousesEntity;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional
public class WarehousesService {

    private final WarehousesDAO warehousesDAO;

    public WarehousesService(WarehousesDAO warehousesDAO) {
        this.warehousesDAO = warehousesDAO;
    }

    @Transactional(readOnly = true)
    public List<WarehousesDTO> findAll() {
        return warehousesDAO.findAll().stream()
                .map(this::toDTO)
                .toList();
    }

    @Transactional(readOnly = true)
    public WarehousesDTO findById(Long id) {
        return toDTO(findEntity(id));
    }

    public WarehousesDTO create(WarehousesDTO dto) {
        if (warehousesDAO.existsByWarehouseCode(dto.getWarehouseCode())) {
            throw new IllegalArgumentException(
                    "倉庫代碼已存在：" + dto.getWarehouseCode()
            );
        }

        WarehousesEntity entity = new WarehousesEntity();
        apply(dto, entity);

        return toDTO(warehousesDAO.save(entity));
    }

    public WarehousesDTO update(Long id, WarehousesDTO dto) {
        WarehousesEntity entity = findEntity(id);

        boolean warehouseCodeChanged =
                !entity.getWarehouseCode().equals(dto.getWarehouseCode());

        if (warehouseCodeChanged
                && warehousesDAO.existsByWarehouseCode(dto.getWarehouseCode())) {
            throw new IllegalArgumentException(
                    "倉庫代碼已存在：" + dto.getWarehouseCode()
            );
        }

        apply(dto, entity);

        return toDTO(warehousesDAO.save(entity));
    }

    public void delete(Long id) {
        warehousesDAO.delete(findEntity(id));
    }

    private WarehousesEntity findEntity(Long id) {
        return warehousesDAO.findById(id)
                .orElseThrow(() ->
                        new EntityNotFoundException("找不到倉庫，ID：" + id)
                );
    }

    private void apply(WarehousesDTO dto, WarehousesEntity entity) {
        entity.setWarehouseCode(dto.getWarehouseCode());
        entity.setName(dto.getName());
        entity.setAddress(dto.getAddress());
        entity.setLat(dto.getLat());
        entity.setLng(dto.getLng());
        entity.setPhone(dto.getPhone());

        if (dto.getIsActive() != null) {
            entity.setIsActive(dto.getIsActive());
        }
    }

    private WarehousesDTO toDTO(WarehousesEntity entity) {
        WarehousesDTO dto = new WarehousesDTO();

        dto.setId(entity.getId());
        dto.setWarehouseCode(entity.getWarehouseCode());
        dto.setName(entity.getName());
        dto.setAddress(entity.getAddress());
        dto.setLat(entity.getLat());
        dto.setLng(entity.getLng());
        dto.setPhone(entity.getPhone());
        dto.setIsActive(entity.getIsActive());

        return dto;
    }
}