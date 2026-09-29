package com.example.backend.service;

import com.example.backend.dao.OrdersDAO;
import com.example.backend.dao.ExceptionCasesDAO;
import com.example.backend.dao.StoresDAO;
import com.example.backend.dao.WarehousesDAO;
import com.example.backend.constants.OrderReviewAction;
import com.example.backend.constants.OrderStatus;
import com.example.backend.constants.ExceptionStatus;
import com.example.backend.constants.ExceptionType;
import com.example.backend.dto.request.OrderReviewRequestDTO;
import com.example.backend.dto.request.OrderItemDTO;
import com.example.backend.dto.request.OrdersDTO;
import com.example.backend.entity.OrderItemsEntity;
import com.example.backend.entity.OrdersEntity;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.criteria.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@Transactional
public class OrdersService {

    private final OrdersDAO ordersDAO;
    private final StoresDAO storesDAO;
    private final WarehousesDAO warehousesDAO;
    private final ExceptionCasesDAO exceptionCasesDAO;

    public OrdersService(
            OrdersDAO ordersDAO,
            StoresDAO storesDAO,
            WarehousesDAO warehousesDAO,
            ExceptionCasesDAO exceptionCasesDAO
    ) {
        this.ordersDAO = ordersDAO;
        this.storesDAO = storesDAO;
        this.warehousesDAO = warehousesDAO;
        this.exceptionCasesDAO = exceptionCasesDAO;
    }

    @Transactional(readOnly = true)
    public List<OrdersDTO> findAll() {
        List<OrdersDTO> result = ordersDAO.findAll().stream().map(this::toDTO).toList();
        Set<Long> automaticOrderIds = new HashSet<>();
        exceptionCasesDAO.findByTypeAndStatusOrderByIdAsc(
                ExceptionType.NO_SIGNATURE, ExceptionStatus.OPEN)
                .forEach(item -> {
                    if (item.getFollowUpOrderId() != null) {
                        automaticOrderIds.add(item.getFollowUpOrderId());
                    }
                });
        result.forEach(item -> item.setAwaitingAutomaticDispatch(
                automaticOrderIds.contains(item.getId())));
        return result;
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
        entity.setStatus(OrderStatus.PENDING_CONFIRM);
        return toDTO(ordersDAO.save(entity));
    }

    @Transactional(readOnly = true)
    public List<OrdersDTO> findByDeliveryDateAndWarehouseId(LocalDate date, Long warehouseId) {
        return ordersDAO.findByDeliveryDateAndWarehouseId(date, warehouseId).
                stream().map(item -> toDTO(item)).toList();
    }

    public List<OrdersDTO> createAll(List<OrdersDTO> dtos) {
        Set<String> seen = new HashSet<>();
        List<OrdersEntity> entities = dtos.stream().map(dto -> {
            if (!seen.add(dto.getOrderNumber())) {
                throw new IllegalArgumentException("同一批資料中訂單編號重複：" + dto.getOrderNumber());
            }
            if (ordersDAO.existsByOrderNumber(dto.getOrderNumber())) {
                throw new IllegalArgumentException("訂單編號已存在：" + dto.getOrderNumber());
            }
            OrdersEntity entity = new OrdersEntity();
            apply(dto, entity);
            entity.setStatus(OrderStatus.PENDING_CONFIRM);
            return entity;
        }).toList();
        return ordersDAO.saveAll(entities).stream().map(this::toDTO).toList();
    }

    public OrdersDTO update(Long id, OrdersDTO dto) {
        requireNotAwaitingAutomaticDispatch(id);
        OrdersEntity entity = findEntity(id);
        if (entity.getRouteId() != null) {
            throw new IllegalArgumentException("訂單已排入路線，請先撤回並解除編組後再修改");
        }
        validateStatusTransition(entity.getStatus(), dto.getStatus());
        if (!entity.getOrderNumber().equals(dto.getOrderNumber()) && ordersDAO.existsByOrderNumber(dto.getOrderNumber())) {
            throw new IllegalArgumentException("訂單編號已存在：" + dto.getOrderNumber());
        }
        apply(dto, entity);
        return toDTO(ordersDAO.save(entity));
    }

    public void delete(Long id) {
        requireNotAwaitingAutomaticDispatch(id);
        OrdersEntity entity = findEntity(id);
        if (entity.getRouteId() != null) {
            throw new IllegalArgumentException("訂單已排入路線，不能刪除");
        }
        if (entity.getStatus() != OrderStatus.PENDING_CONFIRM
                && entity.getStatus() != OrderStatus.CANCELLED) {
            throw new IllegalArgumentException("只有待確認或已取消訂單可以刪除");
        }
        ordersDAO.delete(entity);
    }

    public OrdersDTO review(Long id, OrderReviewRequestDTO request) {
        requireNotAwaitingAutomaticDispatch(id);
        OrdersEntity entity = ordersDAO.findForUpdate(id)
                .orElseThrow(() -> new EntityNotFoundException("找不到訂單，ID：" + id));
        if (request.getAction() == OrderReviewAction.CONFIRM) {
            if (entity.getStatus() != OrderStatus.PENDING_CONFIRM) {
                throw new IllegalArgumentException("只有待確認訂單可以確認");
            }
            applyReviewFields(request, entity);
            entity.setStatus(OrderStatus.CONFIRMED);
        } else if (request.getAction() == OrderReviewAction.UPDATE) {
            requireEditable(entity);
            applyReviewFields(request, entity);
        } else if (request.getAction() == OrderReviewAction.REJECT
                || request.getAction() == OrderReviewAction.CANCEL) {
            requireEditable(entity);
            if (request.getReason() == null || request.getReason().isBlank()) {
                throw new IllegalArgumentException("拒絕或取消訂單時必須填寫原因");
            }
            String prefix = request.getAction() == OrderReviewAction.REJECT ? "拒絕原因：" : "取消原因：";
            entity.setNotes(appendNote(entity.getNotes(), prefix + request.getReason().trim()));
            entity.setStatus(OrderStatus.CANCELLED);
        } else {
            throw new IllegalArgumentException("不支援的訂單操作");
        }
        return toDTO(ordersDAO.save(entity));
    }

    private void requireNotAwaitingAutomaticDispatch(Long orderId) {
        // 排程先鎖異常案件再鎖訂單；這裡先查案件，避免一般訂單確認入口繞過 06:00。
        if (exceptionCasesDAO.existsByFollowUpOrderIdAndTypeAndStatus(
                orderId, ExceptionType.NO_SIGNATURE, ExceptionStatus.OPEN)) {
            throw new IllegalArgumentException("無人簽收重送單將於隔日 06:00 自動送入待排車，不能手動確認或修改");
        }
    }

    private OrdersEntity findEntity(Long id) {
        return ordersDAO.findById(id).orElseThrow(() -> new EntityNotFoundException("找不到訂單，ID：" + id));
    }

    private void apply(OrdersDTO dto, OrdersEntity entity) {
        entity.setOrderNumber(dto.getOrderNumber());
        entity.setStoreId(dto.getStoreId());
        entity.setWarehouseId(dto.getWarehouseId());
        entity.setSourceVendor(dto.getSourceVendor());
        entity.setItemDescription(dto.getItemDescription());
        applyItems(dto.getItems(), entity);
        entity.setBoxCount(dto.getBoxCount());
        entity.setNotes(dto.getNotes());
        entity.setDeliveryDate(dto.getDeliveryDate());
        entity.setAssignedVehicleId(dto.getAssignedVehicleId());
        entity.setAssignedDriverId(dto.getAssignedDriverId());
        entity.setSequence(dto.getSequence());
        if (dto.getStatus() != null) {
            entity.setStatus(dto.getStatus());
        }
    }

    private void applyItems(List<OrderItemDTO> itemDTOs, OrdersEntity order) {
        // 舊版呼叫端沒有 items 欄位時保留既有明細；明確傳 [] 才代表清空。
        if (itemDTOs == null) {
            return;
        }
        order.getItems().clear();
        int fallbackSequence = 1;
        for (OrderItemDTO itemDTO : itemDTOs) {
            OrderItemsEntity item = new OrderItemsEntity();
            item.setProductCode(trimToNull(itemDTO.getProductCode()));
            item.setItemName(itemDTO.getItemName().trim());
            item.setExpectedQuantity(itemDTO.getExpectedQuantity());
            item.setUnit(itemDTO.getUnit().trim());
            item.setSequence(itemDTO.getSequence() == null ? fallbackSequence : itemDTO.getSequence());
            item.setNotes(trimToNull(itemDTO.getNotes()));
            order.addItem(item);
            fallbackSequence++;
        }
    }

    private void applyReviewFields(OrderReviewRequestDTO request, OrdersEntity entity) {
        if (request.getStoreId() != null) {
            if (!storesDAO.existsById(request.getStoreId())) {
                throw new IllegalArgumentException("門市不存在，ID：" + request.getStoreId());
            }
            entity.setStoreId(request.getStoreId());
        }
        if (request.getWarehouseId() != null) {
            if (!warehousesDAO.existsById(request.getWarehouseId())) {
                throw new IllegalArgumentException("倉庫不存在，ID：" + request.getWarehouseId());
            }
            entity.setWarehouseId(request.getWarehouseId());
        }
        if (request.getBoxCount() != null) {
            entity.setBoxCount(request.getBoxCount());
        }
        if (request.getDeliveryDate() != null) {
            entity.setDeliveryDate(request.getDeliveryDate());
        }
        if (request.getSourceVendor() != null) {
            entity.setSourceVendor(request.getSourceVendor().trim());
        }
        if (request.getItemDescription() != null) {
            entity.setItemDescription(request.getItemDescription().trim());
        }
        if (request.getItems() != null) {
            applyItems(request.getItems(), entity);
        }
        if (request.getNotes() != null) {
            entity.setNotes(request.getNotes().trim());
        }
    }

    private void requireEditable(OrdersEntity entity) {
        if (entity.getRouteId() != null) {
            throw new IllegalArgumentException("訂單已排入路線，請先撤回並解除編組後再修改");
        }
        if (entity.getStatus() != OrderStatus.PENDING_CONFIRM
                && entity.getStatus() != OrderStatus.CONFIRMED) {
            throw new IllegalArgumentException("目前訂單狀態不能修改或取消：" + entity.getStatus());
        }
    }

    private void validateStatusTransition(OrderStatus current, OrderStatus requested) {
        if (requested == null || requested == current) {
            return;
        }
        if (current == OrderStatus.PENDING_CONFIRM
                && (requested == OrderStatus.CONFIRMED || requested == OrderStatus.CANCELLED)) {
            return;
        }
        if (current == OrderStatus.CONFIRMED && requested == OrderStatus.CANCELLED) {
            return;
        }
        throw new IllegalArgumentException(
                "訂單狀態不可由 " + current + " 直接改為 " + requested);
    }

    private String appendNote(String original, String addition) {
        if (original == null || original.isBlank()) {
            return addition;
        }
        String combined = original + "；" + addition;
        return combined.length() <= 500 ? combined : combined.substring(0, 500);
    }

    private OrdersDTO toDTO(OrdersEntity entity) {
        OrdersDTO dto = new OrdersDTO();
        dto.setId(entity.getId());
        dto.setOrderNumber(entity.getOrderNumber());
        dto.setStoreId(entity.getStoreId());
        dto.setWarehouseId(entity.getWarehouseId());
        dto.setSourceVendor(entity.getSourceVendor());
        dto.setItemDescription(entity.getItemDescription());
        dto.setItems(entity.getItems().stream().map(this::toItemDTO).toList());
        dto.setBoxCount(entity.getBoxCount());
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

    private OrderItemDTO toItemDTO(OrderItemsEntity entity) {
        OrderItemDTO dto = new OrderItemDTO();
        dto.setId(entity.getId());
        dto.setProductCode(entity.getProductCode());
        dto.setItemName(entity.getItemName());
        dto.setExpectedQuantity(entity.getExpectedQuantity());
        dto.setUnit(entity.getUnit());
        dto.setSequence(entity.getSequence());
        dto.setNotes(entity.getNotes());
        dto.setLoadedQuantity(entity.getLoadedQuantity());
        dto.setCheckedAt(entity.getCheckedAt());
        dto.setCheckedByDriverId(entity.getCheckedByDriverId());
        dto.setLoadingNotes(entity.getLoadingNotes());
        dto.setLoadingMismatchReported(entity.isLoadingMismatchReported());
        return dto;
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
