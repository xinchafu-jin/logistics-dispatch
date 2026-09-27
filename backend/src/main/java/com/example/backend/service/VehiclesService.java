package com.example.backend.service;

import com.example.backend.dao.VehiclesDAO;
import com.example.backend.dto.request.VehiclesDTO;
import com.example.backend.entity.VehiclesEntity;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

@Service
@Transactional
public class VehiclesService {

    private final VehiclesDAO vehiclesDAO;
    private final VehicleMaintenanceService maintenance;
    private final DispatchBoardPushService push;

    public VehiclesService(VehiclesDAO vehiclesDAO, VehicleMaintenanceService maintenance,
            DispatchBoardPushService push) {
        this.vehiclesDAO = vehiclesDAO;
        this.maintenance = maintenance;
        this.push = push;
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
        initializeMileage(dto, entity);
        VehiclesEntity saved = vehiclesDAO.save(entity);
        if (dto.getStatus() != null) {
            maintenance.changeStatus(saved, dto.getStatus());
        }
        push.markResourcesChanged();
        return toDTO(saved);
    }

    public List<VehiclesDTO> createAll(List<VehiclesDTO> dtos) {
        return dtos.stream().map(this::create).toList();
    }

    public VehiclesDTO update(Long id, VehiclesDTO dto) {
        VehiclesEntity entity = findForUpdate(id);
        if (!entity.getPlateNumber().equals(dto.getPlateNumber()) && vehiclesDAO.existsByPlateNumber(dto.getPlateNumber())) {
            throw new IllegalArgumentException("車牌已存在：" + dto.getPlateNumber());
        }
        maintenance.restoreMileageFromHistory(entity);
        assertUnchangedMileage(dto.getCurrentOdometerKm(), entity.getCurrentOdometerKm(), "實際總里程");
        assertUnchangedMileage(dto.getLastMinorMaintenanceKm(), entity.getLastMinorMaintenanceKm(), "小保里程基準");
        assertUnchangedMileage(dto.getLastMajorMaintenanceKm(), entity.getLastMajorMaintenanceKm(), "大保里程基準");
        apply(dto, entity);
        if (dto.getStatus() != null) {
            maintenance.changeStatus(entity, dto.getStatus());
        }
        push.markResourcesChanged();
        return toDTO(vehiclesDAO.save(entity));
    }

    public void delete(Long id) {
        VehiclesEntity entity = findForUpdate(id);
        if (maintenance.hasHistory(id)) {
            throw new IllegalArgumentException("車輛已有保養／維修紀錄，請改為退役以保留歷史。");
        }
        vehiclesDAO.delete(entity);
        push.markResourcesChanged();
    }

    private VehiclesEntity findEntity(Long id) {
        return vehiclesDAO.findById(id).orElseThrow(() -> new EntityNotFoundException("找不到車輛，ID：" + id));
    }

    private VehiclesEntity findForUpdate(Long id) {
        return vehiclesDAO.findByIdForUpdate(id)
                .orElseThrow(() -> new EntityNotFoundException("找不到車輛，ID：" + id));
    }

    private void initializeMileage(VehiclesDTO dto, VehiclesEntity entity) {
        Integer current = dto.getCurrentOdometerKm();
        Integer minor = dto.getLastMinorMaintenanceKm();
        Integer major = dto.getLastMajorMaintenanceKm();
        if ((minor != null && (current == null || minor > current))
                || (major != null && (current == null || major > current))) {
            throw new IllegalArgumentException("初始保養基準不能大於實際總里程。");
        }
        entity.setCurrentOdometerKm(current);
        // 明確建檔為 0 km 的新車才初始化為 0；缺資料或舊車不可猜測。
        entity.setLastMinorMaintenanceKm(minor != null ? minor : Integer.valueOf(0).equals(current) ? 0 : null);
        entity.setLastMajorMaintenanceKm(major != null ? major : Integer.valueOf(0).equals(current) ? 0 : null);
    }

    private void assertUnchangedMileage(Integer supplied, Integer actual, String label) {
        if (supplied != null && !Objects.equals(supplied, actual)) {
            throw new IllegalArgumentException(label + "只能由實際出車或保養完成紀錄更新，不能手動覆寫。");
        }
    }

    private void apply(VehiclesDTO dto, VehiclesEntity entity) {
        entity.setPlateNumber(dto.getPlateNumber());
        entity.setVehicleType(dto.getVehicleType());
        entity.setCapacity(dto.getCapacity());
        entity.setFuelConsumption(dto.getFuelConsumption());
        entity.setWarehouseId(dto.getWarehouseId());
        if (dto.getTonnage() != null) {
            entity.setTonnage(dto.getTonnage());
        }
    }

    private VehiclesDTO toDTO(VehiclesEntity entity) {
        VehiclesEntity snapshot = maintenance.mileageSnapshot(entity);
        VehiclesDTO dto = new VehiclesDTO();
        dto.setId(entity.getId());
        dto.setPlateNumber(entity.getPlateNumber());
        dto.setVehicleType(entity.getVehicleType());
        dto.setCapacity(entity.getCapacity());
        dto.setFuelConsumption(entity.getFuelConsumption());
        dto.setCumulativeMileageKm(entity.getCumulativeMileageKm());
        dto.setTonnage(entity.getTonnage());
        dto.setCurrentOdometerKm(snapshot.getCurrentOdometerKm());
        dto.setLastMinorMaintenanceKm(snapshot.getLastMinorMaintenanceKm());
        dto.setLastMajorMaintenanceKm(snapshot.getLastMajorMaintenanceKm());
        dto.setMaintenance(maintenance.summary(snapshot, null));
        dto.setStatus(entity.getStatus());
        dto.setWarehouseId(entity.getWarehouseId());
        return dto;
    }
}
