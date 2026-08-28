package com.example.backend.service;

import com.example.backend.dao.VehiclesDAO;
import com.example.backend.dto.request.VehiclesDTO;
import com.example.backend.entity.VehiclesEntity;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional
public class VehiclesService {

    private final VehiclesDAO vehiclesDAO;

    public VehiclesService(VehiclesDAO vehiclesDAO) {
        this.vehiclesDAO = vehiclesDAO;
    }

    @Transactional(readOnly = true)
    public List<VehiclesDTO> findAll() {
        return vehiclesDAO.findAll().stream().map(this::toDTO).toList();
    }

    @Transactional(readOnly = true)
    public VehiclesDTO findById(Long id) {
        return toDTO(findEntity(id));
    }

    public VehiclesDTO create(VehiclesDTO dto) {
        if (vehiclesDAO.existsByPlateNumber(dto.getPlateNumber())) {
            throw new IllegalArgumentException("車牌已存在：" + dto.getPlateNumber());
        }
        VehiclesEntity entity = new VehiclesEntity();
        apply(dto, entity);
        return toDTO(vehiclesDAO.save(entity));
    }

    public List<VehiclesDTO> createAll(List<VehiclesDTO> dtos) {
        List<VehiclesEntity> entities = dtos.stream().map(dto -> {
            if (vehiclesDAO.existsByPlateNumber(dto.getPlateNumber())) {
                throw new IllegalArgumentException("車牌已存在：" + dto.getPlateNumber());
            }
            VehiclesEntity entity = new VehiclesEntity();
            apply(dto, entity);
            return entity;
        }).toList();
        return vehiclesDAO.saveAll(entities).stream().map(this::toDTO).toList();
    }

    public VehiclesDTO update(Long id, VehiclesDTO dto) {
        VehiclesEntity entity = findEntity(id);
        if (!entity.getPlateNumber().equals(dto.getPlateNumber()) && vehiclesDAO.existsByPlateNumber(dto.getPlateNumber())) {
            throw new IllegalArgumentException("車牌已存在：" + dto.getPlateNumber());
        }
        apply(dto, entity);
        return toDTO(vehiclesDAO.save(entity));
    }

    public void delete(Long id) {
        vehiclesDAO.delete(findEntity(id));
    }

    private VehiclesEntity findEntity(Long id) {
        return vehiclesDAO.findById(id).orElseThrow(() -> new EntityNotFoundException("找不到車輛，ID：" + id));
    }

    private void apply(VehiclesDTO dto, VehiclesEntity entity) {
        entity.setWarehouseId(dto.getWarehouseId());
        entity.setPlateNumber(dto.getPlateNumber());
        entity.setVehicleType(dto.getVehicleType());
        entity.setCapacity(dto.getCapacity());
        entity.setFuelConsumption(dto.getFuelConsumption());
        if (dto.getStatus() != null) {
            entity.setStatus(dto.getStatus());
        }
    }

    private VehiclesDTO toDTO(VehiclesEntity entity) {
        VehiclesDTO dto = new VehiclesDTO();
        dto.setId(entity.getId());
        dto.setWarehouseId(entity.getWarehouseId());
        dto.setPlateNumber(entity.getPlateNumber());
        dto.setVehicleType(entity.getVehicleType());
        dto.setCapacity(entity.getCapacity());
        dto.setFuelConsumption(entity.getFuelConsumption());
        dto.setStatus(entity.getStatus());
        return dto;
    }
}
