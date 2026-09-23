package com.example.backend.service;

import com.example.backend.constants.OrderStatus;
import com.example.backend.constants.StoreStatus;
import com.example.backend.dao.StoresDAO;
import com.example.backend.dao.WarehousesDAO;
import com.example.backend.dto.request.OrdersBatchDto;
import com.example.backend.dto.request.OrdersDTO;
import com.example.backend.dto.respones.OrderImportValidationResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class OrderImportService {

    private final OrdersService ordersService;
    private final StoresDAO storesDAO;
    private final WarehousesDAO warehousesDAO;

    public OrderImportService(
            OrdersService ordersService,
            StoresDAO storesDAO,
            WarehousesDAO warehousesDAO
    ) {
        this.ordersService = ordersService;
        this.storesDAO = storesDAO;
        this.warehousesDAO = warehousesDAO;
    }

    @Transactional(readOnly = true)
    public OrderImportValidationResponse validate(OrdersBatchDto request) {
        List<OrdersDTO> rows = request == null || request.getOrders() == null
                ? List.of() : request.getOrders();
        List<OrderImportValidationResponse.RowError> errors = new ArrayList<>();
        if (rows.isEmpty()) {
            errors.add(new OrderImportValidationResponse.RowError(0, "orders", "至少需要一筆訂單"));
        }
        for (int index = 0; index < rows.size(); index++) {
            validateRow(rows.get(index), index + 1, errors);
        }
        OrderImportValidationResponse response = new OrderImportValidationResponse();
        response.setTotalRows(rows.size());
        response.setErrors(errors);
        long invalidRows = errors.stream().map(OrderImportValidationResponse.RowError::getRowNumber)
                .filter(row -> row > 0).distinct().count();
        response.setValidRows(rows.size() - (int) invalidRows);
        response.setValid(errors.isEmpty());
        return response;
    }

    @Transactional
    public List<OrdersDTO> confirm(OrdersBatchDto request) {
        OrderImportValidationResponse validation = validate(request);
        if (!validation.isValid()) {
            String first = validation.getErrors().getFirst().getMessage();
            throw new IllegalArgumentException("訂單匯入驗證失敗：" + first);
        }
        List<OrdersDTO> normalized = request.getOrders().stream().map(this::normalize).toList();
        return ordersService.createAll(normalized);
    }

    private void validateRow(
            OrdersDTO row,
            int rowNumber,
            List<OrderImportValidationResponse.RowError> errors
    ) {
        if (row == null) {
            errors.add(new OrderImportValidationResponse.RowError(rowNumber, "row", "此列沒有資料"));
            return;
        }
        if (row.getStoreId() == null || !storesDAO.existsById(row.getStoreId())) {
            errors.add(new OrderImportValidationResponse.RowError(rowNumber, "storeId", "門市不存在"));
        } else if (storesDAO.findById(row.getStoreId())
                .map(store -> store.getStatus() != StoreStatus.ACTIVE).orElse(false)) {
            errors.add(new OrderImportValidationResponse.RowError(rowNumber, "storeId", "門市目前未啟用"));
        }
        if (row.getWarehouseId() == null || !warehousesDAO.existsById(row.getWarehouseId())) {
            errors.add(new OrderImportValidationResponse.RowError(rowNumber, "warehouseId", "倉庫不存在"));
        } else if (warehousesDAO.findById(row.getWarehouseId())
                .map(warehouse -> !Boolean.TRUE.equals(warehouse.getIsActive())).orElse(false)) {
            errors.add(new OrderImportValidationResponse.RowError(rowNumber, "warehouseId", "倉庫目前未啟用"));
        }
        if (row.getBoxCount() == null || row.getBoxCount() < 1) {
            errors.add(new OrderImportValidationResponse.RowError(rowNumber, "boxCount", "箱數至少為 1"));
        }
        if (row.getDeliveryDate() == null) {
            errors.add(new OrderImportValidationResponse.RowError(rowNumber, "deliveryDate", "配送日期不能為空"));
        }
        length(row.getSourceVendor(), 100, rowNumber, "sourceVendor", errors);
        length(row.getItemDescription(), 255, rowNumber, "itemDescription", errors);
        length(row.getNotes(), 500, rowNumber, "notes", errors);
    }

    private void length(
            String value, int max, int row, String field,
            List<OrderImportValidationResponse.RowError> errors
    ) {
        if (value != null && value.length() > max) {
            errors.add(new OrderImportValidationResponse.RowError(
                    row, field, field + " 不可超過 " + max + " 字"));
        }
    }

    private OrdersDTO normalize(OrdersDTO source) {
        OrdersDTO target = new OrdersDTO();
        target.setOrderNumber("DO-" + source.getDeliveryDate().format(DateTimeFormatter.BASIC_ISO_DATE)
                + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        target.setStoreId(source.getStoreId());
        target.setWarehouseId(source.getWarehouseId());
        target.setSourceVendor(source.getSourceVendor());
        target.setItemDescription(source.getItemDescription());
        target.setBoxCount(source.getBoxCount());
        target.setNotes(source.getNotes());
        target.setDeliveryDate(source.getDeliveryDate());
        target.setStatus(OrderStatus.PENDING_CONFIRM);
        return target;
    }
}
