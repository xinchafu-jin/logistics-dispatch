package com.example.backend.service;

import com.example.backend.constants.StoreStatus;
import com.example.backend.dao.StoresDAO;
import com.example.backend.dto.request.StoresDTO;
import com.example.backend.entity.StoresEntity;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional
public class StoresService {

    private final StoresDAO storesDAO;

    public StoresService(StoresDAO storesDAO) {
        this.storesDAO = storesDAO;
    }

    @Transactional(readOnly = true)
    public List<StoresDTO> findAll() {
        return storesDAO.findAll().stream().map(this::toDTO).toList();
    }

    @Transactional(readOnly = true)
    public StoresDTO findById(Long id) {
        return toDTO(findEntity(id));
    }

    public StoresDTO create(StoresDTO dto) {
        if (storesDAO.existsByStoreCode(dto.getStoreCode())) {
            throw new IllegalArgumentException("門市代碼已存在：" + dto.getStoreCode());
        }
        StoresEntity entity = new StoresEntity();
        apply(dto, entity);
        return toDTO(storesDAO.save(entity));
    }

    public List<StoresDTO> createAll(List<StoresDTO> dtos) {
        List<StoresEntity> entities = dtos.stream().map(dto -> {
            if (storesDAO.existsByStoreCode(dto.getStoreCode())) {
                throw new IllegalArgumentException("門市代碼已存在：" + dto.getStoreCode());
            }
            StoresEntity entity = new StoresEntity();
            apply(dto, entity);
            return entity;
        }).toList();
        return storesDAO.saveAll(entities).stream().map(this::toDTO).toList();
    }

    public StoresDTO update(Long id, StoresDTO dto) {
        StoresEntity entity = findEntity(id);
        if (!entity.getStoreCode().equals(dto.getStoreCode()) && storesDAO.existsByStoreCode(dto.getStoreCode())) {
            throw new IllegalArgumentException("門市代碼已存在：" + dto.getStoreCode());
        }
        apply(dto, entity);
        return toDTO(storesDAO.save(entity));
    }

    public StoresDTO updateStatus(Long id, StoreStatus status) {
        StoresEntity entity = findEntity(id);
        entity.setStatus(status);
        return toDTO(storesDAO.save(entity));
    }

    public void delete(Long id) {
        storesDAO.delete(findEntity(id));
    }

    private StoresEntity findEntity(Long id) {
        return storesDAO.findById(id).orElseThrow(() -> new EntityNotFoundException("找不到門市，ID：" + id));
    }

    private void apply(StoresDTO dto, StoresEntity entity) {
        entity.setStoreCode(dto.getStoreCode());
        entity.setName(dto.getName());
        entity.setAddress(dto.getAddress());
        entity.setLat(dto.getLat());
        entity.setLng(dto.getLng());
        entity.setContactName(dto.getContactName());
        entity.setPhone(dto.getPhone());
        entity.setReceivingStart(dto.getReceivingStart());
        entity.setReceivingEnd(dto.getReceivingEnd());
        entity.setNotes(dto.getNotes());
        if (dto.getStatus() != null) {
            entity.setStatus(dto.getStatus());
        }
    }

    private StoresDTO toDTO(StoresEntity entity) {
        StoresDTO dto = new StoresDTO();
        dto.setId(entity.getId());
        dto.setStoreCode(entity.getStoreCode());
        dto.setName(entity.getName());
        dto.setAddress(entity.getAddress());
        dto.setLat(entity.getLat());
        dto.setLng(entity.getLng());
        dto.setContactName(entity.getContactName());
        dto.setPhone(entity.getPhone());
        dto.setReceivingStart(entity.getReceivingStart());
        dto.setReceivingEnd(entity.getReceivingEnd());
        dto.setNotes(entity.getNotes());
        dto.setStatus(entity.getStatus());
        return dto;
    }
}