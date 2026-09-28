package com.example.backend.service;

import com.example.backend.constants.MileageCorrectionField;
import com.example.backend.constants.VehicleStatus;
import com.example.backend.dao.MileageLogsDAO;
import com.example.backend.dao.VehicleMileageCorrectionsDAO;
import com.example.backend.dao.VehiclesDAO;
import com.example.backend.dto.request.VehicleMileageCorrectionRequestDTO;
import com.example.backend.dto.request.VehiclesDTO;
import com.example.backend.dto.respones.VehicleMileageCorrectionResponse;
import com.example.backend.entity.MileageLogsEntity;
import com.example.backend.entity.VehicleMileageCorrectionsEntity;
import com.example.backend.entity.VehiclesEntity;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
@Transactional
public class VehiclesService {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    private final VehiclesDAO vehiclesDAO;
    private final VehicleMaintenanceService vehicleMaintenanceService;
    private final MileageLogsDAO mileageLogsDAO;
    private final VehicleMileageCorrectionsDAO correctionsDAO;

    public VehiclesService(
            VehiclesDAO vehiclesDAO,
            VehicleMaintenanceService vehicleMaintenanceService,
            MileageLogsDAO mileageLogsDAO,
            VehicleMileageCorrectionsDAO correctionsDAO
    ) {
        this.vehiclesDAO = vehiclesDAO;
        this.vehicleMaintenanceService = vehicleMaintenanceService;
        this.mileageLogsDAO = mileageLogsDAO;
        this.correctionsDAO = correctionsDAO;
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
        // 狀態走 changeStatus、不直接 set：新車一建好就是「送小保」這類狀態時，也要有一筆送修紀錄
        vehicleMaintenanceService.changeStatus(saved, statusOf(dto));
        return toDTO(saved);
    }

    /** 一次新增多台；同一個交易，任何一台失敗就全部不算 */
    public List<VehiclesDTO> createAll(List<VehiclesDTO> dtos) {
        List<VehiclesDTO> created = new ArrayList<>();
        for (VehiclesDTO dto : dtos) {
            created.add(create(dto));
        }
        return created;
    }

    public VehiclesDTO update(Long id, VehiclesDTO dto) {
        // 鎖住這台車：出車時會寫回行車紀錄器里程，跟這裡改里程、改狀態要排隊
        VehiclesEntity entity = vehiclesDAO.findByIdForUpdate(id)
                .orElseThrow(() -> new EntityNotFoundException("找不到車輛，ID：" + id));
        if (!entity.getPlateNumber().equals(dto.getPlateNumber()) && vehiclesDAO.existsByPlateNumber(dto.getPlateNumber())) {
            throw new IllegalArgumentException("車牌已存在：" + dto.getPlateNumber());
        }
        Integer current = fillOnce(dto.getCurrentOdometerKm(), entity.getCurrentOdometerKm(), "行車紀錄器里程");
        Integer minor = fillOnce(dto.getLastMinorMaintenanceKm(), entity.getLastMinorMaintenanceKm(), "小保基準");
        Integer major = fillOnce(dto.getLastMajorMaintenanceKm(), entity.getLastMajorMaintenanceKm(), "大保基準");
        boolean filled = !Objects.equals(current, entity.getCurrentOdometerKm())
                || !Objects.equals(minor, entity.getLastMinorMaintenanceKm())
                || !Objects.equals(major, entity.getLastMajorMaintenanceKm());
        entity.setCurrentOdometerKm(current);
        entity.setLastMinorMaintenanceKm(minor);
        entity.setLastMajorMaintenanceKm(major);
        // 只在這次有補值時才檢查：司機填錯、里程比上次保養還小的車，主管改別的欄位時不該被擋
        if (filled) {
            requireBaselinesNotAhead(entity);
        }
        apply(dto, entity);
        if (dto.getStatus() != null) {
            vehicleMaintenanceService.changeStatus(entity, dto.getStatus());
        }
        return toDTO(vehiclesDAO.save(entity));
    }

    /**
     * 主管更正行車紀錄器里程與保養基準（打錯時用）。平常這三個數字只能補一次（fillOnce），
     * 更正是另一條路：一定要寫原因，改到的每個數字都留一筆紀錄（誰、何時、從多少改成多少）。
     *
     * <p>車在外面跑（出車還沒收車）時，車上的里程就是這趟的出車讀數，兩邊要一起改：
     * 收車時會擋「收車讀數小於出車讀數」，也只在「車上的里程＝出車讀數」時才寫回
     * （VehiclesDAO.updateCurrentOdometer），只改一邊的話司機就收不了車。</p>
     */
    public VehiclesDTO correctMileage(Long id, VehicleMileageCorrectionRequestDTO dto, String correctedBy) {
        // 跟修改車輛一樣先鎖車：出車、收車也會改車上的里程，要排隊
        VehiclesEntity entity = vehiclesDAO.findByIdForUpdate(id)
                .orElseThrow(() -> new EntityNotFoundException("找不到車輛，ID：" + id));
        Integer current = changedValue(dto.getCurrentOdometerKm(), entity.getCurrentOdometerKm());
        Integer minor = changedValue(dto.getLastMinorMaintenanceKm(), entity.getLastMinorMaintenanceKm());
        Integer major = changedValue(dto.getLastMajorMaintenanceKm(), entity.getLastMajorMaintenanceKm());
        if (current == null && minor == null && major == null) {
            throw new IllegalArgumentException("數字跟現在一樣，沒有要更正的");
        }

        List<VehicleMileageCorrectionsEntity> corrections = new ArrayList<>();
        VehicleMileageCorrectionsEntity currentCorrection = null;
        if (current != null) {
            currentCorrection = newCorrection(id, MileageCorrectionField.CURRENT_ODOMETER, entity.getCurrentOdometerKm(), current);
            corrections.add(currentCorrection);
            entity.setCurrentOdometerKm(current);
        }
        if (minor != null) {
            corrections.add(newCorrection(id, MileageCorrectionField.MINOR_BASELINE, entity.getLastMinorMaintenanceKm(), minor));
            entity.setLastMinorMaintenanceKm(minor);
        }
        if (major != null) {
            corrections.add(newCorrection(id, MileageCorrectionField.MAJOR_BASELINE, entity.getLastMajorMaintenanceKm(), major));
            entity.setLastMajorMaintenanceKm(major);
        }
        // 更正完還是要合理：上次保養的里程不能比現在大（要一起改的話，一次送兩個數字）
        requireBaselinesNotAhead(entity);
        if (currentCorrection != null) {
            currentCorrection.setMileageLogId(correctOpenTripStart(id, current));
        }

        String reason = dto.getReason().trim();
        LocalDateTime now = LocalDateTime.now(TAIPEI);
        for (VehicleMileageCorrectionsEntity correction : corrections) {
            correction.setReason(reason);
            correction.setCorrectedBy(correctedBy);
            correction.setCorrectedAt(now);
        }
        correctionsDAO.saveAll(corrections);
        return toDTO(vehiclesDAO.save(entity));
    }

    /** 這台車的里程更正紀錄，新的在前 */
    @Transactional(readOnly = true)
    public List<VehicleMileageCorrectionResponse> mileageCorrections(Long id) {
        findEntity(id);
        List<VehicleMileageCorrectionResponse> responses = new ArrayList<>();
        for (VehicleMileageCorrectionsEntity correction : correctionsDAO.findAllByVehicleIdOrderByIdDesc(id)) {
            responses.add(toCorrectionResponse(correction));
        }
        return responses;
    }

    public void delete(Long id) {
        VehiclesEntity entity = findEntity(id);
        if (vehicleMaintenanceService.hasHistory(id) || correctionsDAO.existsByVehicleId(id)) {
            throw new IllegalArgumentException("這台車有保養、維修或里程更正紀錄，請改成退役以保留歷史");
        }
        vehiclesDAO.delete(entity);
    }

    private VehiclesEntity findEntity(Long id) {
        return vehiclesDAO.findById(id).orElseThrow(() -> new EntityNotFoundException("找不到車輛，ID：" + id));
    }

    /**
     * 新增車輛時的里程與保養基準：新車填 0，大小保基準自動是 0；
     * 舊車填建檔時的行車紀錄器里程和已知的基準，不知道就留空，之後可以補一次。
     */
    private void initializeMileage(VehiclesDTO dto, VehiclesEntity entity) {
        Integer current = dto.getCurrentOdometerKm();
        entity.setCurrentOdometerKm(current);
        entity.setLastMinorMaintenanceKm(dto.getLastMinorMaintenanceKm());
        entity.setLastMajorMaintenanceKm(dto.getLastMajorMaintenanceKm());
        if (Integer.valueOf(0).equals(current)) {
            if (entity.getLastMinorMaintenanceKm() == null) {
                entity.setLastMinorMaintenanceKm(0);
            }
            if (entity.getLastMajorMaintenanceKm() == null) {
                entity.setLastMajorMaintenanceKm(0);
            }
        }
        requireBaselinesNotAhead(entity);
    }

    /**
     * 行車紀錄器里程和保養基準：還是空的（這個功能上線前的舊車）可以補一次；
     * 已經有值就只能由出車、收車、保養完成更新，不能在一般的修改裡改，免得為了讓車「過關」去改數字。
     * 打錯了走 correctMileage，要寫原因、留紀錄。
     * 前端編輯時會把目前的值原樣送回來，所以「送來的跟現在一樣」不算修改。
     */
    private Integer fillOnce(Integer supplied, Integer current, String label) {
        if (supplied == null || supplied.equals(current)) {
            return current;
        }
        if (current != null) {
            throw new IllegalArgumentException(label + "已經有紀錄，平常只能由出車、收車或保養完成更新；打錯了請用「更正里程」");
        }
        return supplied;
    }

    /** 更正時送來的數字：沒帶、或跟現在一樣就是不改（回傳 null） */
    private Integer changedValue(Integer supplied, Integer current) {
        if (supplied == null || supplied.equals(current)) {
            return null;
        }
        return supplied;
    }

    private VehicleMileageCorrectionsEntity newCorrection(
            Long vehicleId,
            MileageCorrectionField field,
            Integer oldKm,
            Integer newKm
    ) {
        VehicleMileageCorrectionsEntity correction = new VehicleMileageCorrectionsEntity();
        correction.setVehicleId(vehicleId);
        correction.setField(field);
        correction.setOldKm(oldKm);
        correction.setNewKm(newKm);
        return correction;
    }

    /**
     * 車在外面跑時，一起改那一趟的出車讀數，回傳那一趟的 ID；沒在跑就回傳 null。
     * 出車時會擋「這台車還有別的司機沒收車」，正常最多一趟；真的有兩趟以上就不猜要改哪一趟
     */
    private Long correctOpenTripStart(Long vehicleId, int startOdometer) {
        List<MileageLogsEntity> openTrips = mileageLogsDAO.findOpenByVehicleForUpdate(vehicleId);
        if (openTrips.isEmpty()) {
            return null;
        }
        if (openTrips.size() > 1) {
            throw new IllegalArgumentException("這台車有兩筆以上還沒收車的里程紀錄，請先處理完再更正");
        }
        MileageLogsEntity openTrip = openTrips.get(0);
        openTrip.setStartOdometer(startOdometer);
        mileageLogsDAO.save(openTrip);
        return openTrip.getId();
    }

    private VehicleMileageCorrectionResponse toCorrectionResponse(VehicleMileageCorrectionsEntity correction) {
        VehicleMileageCorrectionResponse response = new VehicleMileageCorrectionResponse();
        response.setId(correction.getId());
        response.setField(correction.getField());
        response.setOldKm(correction.getOldKm());
        response.setNewKm(correction.getNewKm());
        response.setReason(correction.getReason());
        response.setMileageLogId(correction.getMileageLogId());
        response.setCorrectedBy(correction.getCorrectedBy());
        response.setCorrectedAt(correction.getCorrectedAt());
        return response;
    }

    /** 上次保養時的里程不可能比現在的里程還大 */
    private void requireBaselinesNotAhead(VehiclesEntity entity) {
        Integer current = entity.getCurrentOdometerKm();
        Integer minor = entity.getLastMinorMaintenanceKm();
        Integer major = entity.getLastMajorMaintenanceKm();
        if ((minor != null || major != null) && current == null) {
            throw new IllegalArgumentException("要先填目前的行車紀錄器里程，才能填保養基準");
        }
        if (minor != null && minor > current) {
            throw new IllegalArgumentException("小保基準不能大於目前的行車紀錄器里程");
        }
        if (major != null && major > current) {
            throw new IllegalArgumentException("大保基準不能大於目前的行車紀錄器里程");
        }
    }

    private VehicleStatus statusOf(VehiclesDTO dto) {
        if (dto.getStatus() == null) {
            return VehicleStatus.AVAILABLE;
        }
        return dto.getStatus();
    }

    /**
     * 保養與退役規則三個一起填或都不填，大保間隔不能比小保短。
     * 規則是主管決定的，一定填得出來；分開填容易漏掉退役總里程，漏了那台車就永遠不會因為該退役被擋。
     * 基準不一樣：那是過去發生的里程，舊車可能真的查不到，所以可以只填一個
     */
    private void requireCompleteMaintenanceRules(VehiclesDTO dto) {
        Integer minor = dto.getMinorMaintenanceIntervalKm();
        Integer major = dto.getMajorMaintenanceIntervalKm();
        Integer retirement = dto.getRetirementKm();
        if (minor == null && major == null && retirement == null) {
            return;
        }
        if (minor == null || major == null || retirement == null) {
            throw new IllegalArgumentException("小保間隔、大保間隔、退役總里程要一起填");
        }
        if (major < minor) {
            throw new IllegalArgumentException("大保間隔不能小於小保間隔");
        }
    }

    /** 狀態不在這裡改：要經過 VehicleMaintenanceService.changeStatus 才會建立或完成送修紀錄 */
    private void apply(VehiclesDTO dto, VehiclesEntity entity) {
        requireCompleteMaintenanceRules(dto);
        entity.setPlateNumber(dto.getPlateNumber());
        entity.setVehicleType(dto.getVehicleType());
        entity.setCapacity(dto.getCapacity());
        entity.setFuelConsumption(dto.getFuelConsumption());
        entity.setWarehouseId(dto.getWarehouseId());
        // 規則跟基準不同，不是「只能補一次」：送什麼就存什麼，留白就清掉
        entity.setMinorMaintenanceIntervalKm(dto.getMinorMaintenanceIntervalKm());
        entity.setMajorMaintenanceIntervalKm(dto.getMajorMaintenanceIntervalKm());
        entity.setRetirementKm(dto.getRetirementKm());
    }

    private VehiclesDTO toDTO(VehiclesEntity entity) {
        VehiclesDTO dto = new VehiclesDTO();
        dto.setId(entity.getId());
        dto.setPlateNumber(entity.getPlateNumber());
        dto.setVehicleType(entity.getVehicleType());
        dto.setCapacity(entity.getCapacity());
        dto.setFuelConsumption(entity.getFuelConsumption());
        dto.setCumulativeMileageKm(entity.getCumulativeMileageKm());
        dto.setStatus(entity.getStatus());
        dto.setWarehouseId(entity.getWarehouseId());
        dto.setMinorMaintenanceIntervalKm(entity.getMinorMaintenanceIntervalKm());
        dto.setMajorMaintenanceIntervalKm(entity.getMajorMaintenanceIntervalKm());
        dto.setRetirementKm(entity.getRetirementKm());
        dto.setCurrentOdometerKm(entity.getCurrentOdometerKm());
        dto.setLastMinorMaintenanceKm(entity.getLastMinorMaintenanceKm());
        dto.setLastMajorMaintenanceKm(entity.getLastMajorMaintenanceKm());
        dto.setMaintenance(vehicleMaintenanceService.summary(entity, null));
        return dto;
    }
}
