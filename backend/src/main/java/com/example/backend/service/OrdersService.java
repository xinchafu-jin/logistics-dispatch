package com.example.backend.service;

import com.example.backend.dao.OrdersDAO;
import com.example.backend.dto.OrdersDTO;
import com.example.backend.entity.OrdersEntity;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional
public class OrdersService {

    private final OrdersDAO ordersDAO;

    public OrdersService(OrdersDAO ordersDAO) {
        this.ordersDAO = ordersDAO;
    }

    @Transactional(readOnly = true)
    public List<OrdersDTO> findAll() {
        return ordersDAO.findAll().stream().map(this::toDTO).toList();
    }

    @Transactional(readOnly = true)
    public OrdersDTO findById(Long id) {
        return toDTO(findEntity(id));
    }

    public OrdersDTO create(OrdersDTO dto) {
        if (ordersDAO.existsByOrderNumber(dto.getOrderNumber())) {
            throw new IllegalArgumentException("訂單編號已存在：" + dto.getOrderNumber());
        }
        OrdersEntity entity = new OrdersEntity();
        apply(dto, entity);
        return toDTO(ordersDAO.save(entity));
    }

    public List<OrdersDTO> createAll(List<OrdersDTO> dtos) {
        List<OrdersEntity> entities = dtos.stream().map(dto -> {
            if (ordersDAO.existsByOrderNumber(dto.getOrderNumber())) {
                throw new IllegalArgumentException("訂單編號已存在：" + dto.getOrderNumber());
            }
            OrdersEntity entity = new OrdersEntity();
            apply(dto, entity);
            return entity;
        }).toList();
        return ordersDAO.saveAll(entities).stream().map(this::toDTO).toList();
    }

    public OrdersDTO update(Long id, OrdersDTO dto) {
        OrdersEntity entity = findEntity(id);
        if (!entity.getOrderNumber().equals(dto.getOrderNumber()) && ordersDAO.existsByOrderNumber(dto.getOrderNumber())) {
            throw new IllegalArgumentException("訂單編號已存在：" + dto.getOrderNumber());
        }
        apply(dto, entity);
        return toDTO(ordersDAO.save(entity));
    }

    public void delete(Long id) {
        ordersDAO.delete(findEntity(id));
    }

    private OrdersEntity findEntity(Long id) {
        return ordersDAO.findById(id).orElseThrow(() -> new EntityNotFoundException("找不到訂單，ID：" + id));
    }

    private void apply(OrdersDTO dto, OrdersEntity entity) {
        entity.setOrderNumber(dto.getOrderNumber());
        entity.setStoreId(dto.getStoreId());
        entity.setSourceVendor(dto.getSourceVendor());
        entity.setItemDescription(dto.getItemDescription());
        entity.setBoxCount(dto.getBoxCount());
        entity.setVolume(dto.getVolume());
        entity.setNotes(dto.getNotes());
        entity.setDeliveryDate(dto.getDeliveryDate());
        entity.setAssignedVehicleId(dto.getAssignedVehicleId());
        entity.setAssignedDriverId(dto.getAssignedDriverId());
        entity.setSequence(dto.getSequence());
        if (dto.getStatus() != null) {
            entity.setStatus(dto.getStatus());
        }
    }

    private OrdersDTO toDTO(OrdersEntity entity) {
        OrdersDTO dto = new OrdersDTO();
        dto.setId(entity.getId());
        dto.setOrderNumber(entity.getOrderNumber());
        dto.setStoreId(entity.getStoreId());
        dto.setSourceVendor(entity.getSourceVendor());
        dto.setItemDescription(entity.getItemDescription());
        dto.setBoxCount(entity.getBoxCount());
        dto.setVolume(entity.getVolume());
        dto.setNotes(entity.getNotes());
        dto.setDeliveryDate(entity.getDeliveryDate());
        dto.setStatus(entity.getStatus());
        dto.setAssignedVehicleId(entity.getAssignedVehicleId());
        dto.setAssignedDriverId(entity.getAssignedDriverId());
        dto.setSequence(entity.getSequence());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        return dto;
    }
}