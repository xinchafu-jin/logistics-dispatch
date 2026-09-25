package com.example.backend.service;

import com.example.backend.dao.DispatchTemplatesDAO;
import com.example.backend.dao.DriversDAO;
import com.example.backend.dao.StoresDAO;
import com.example.backend.dao.TemplateRoutesDAO;
import com.example.backend.dao.TemplateStopsDAO;
import com.example.backend.dao.VehiclesDAO;
import com.example.backend.dao.WarehousesDAO;
import com.example.backend.dto.request.OptimizeSlotsDTO;
import com.example.backend.dto.respones.DispatchResponse;
import com.example.backend.entity.DispatchTemplatesEntity;
import com.example.backend.entity.TemplateRoutesEntity;
import com.example.backend.entity.VehiclesEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 套用編組：格子按倉分組、每倉交給 optimizeSlots 排一次。
 *
 * <p>排車本身（自動配車、班表、已發布要擋）由 optimizeSlots 負責，這裡用 mock 取代，
 * 只看送進去的格子對不對。編組 7：台北倉（1）兩格、台中倉（2）一格。</p>
 */
class TemplatesServiceApplyTest {

    private static final Long TEMPLATE_ID = 7L;
    private static final LocalDate DATE = LocalDate.of(2026, 9, 26);

    private final List<TemplateRoutesEntity> slots = new ArrayList<>();
    private VehiclesDAO vehiclesDAO;
    private DispatchWorkflowService dispatchWorkflowService;
    private TemplatesService service;
    private long nextSlotId = 1;

    @BeforeEach
    void setUp() {
        DispatchTemplatesDAO dispatchTemplatesDAO = mock(DispatchTemplatesDAO.class);
        TemplateRoutesDAO templateRoutesDAO = mock(TemplateRoutesDAO.class);
        vehiclesDAO = mock(VehiclesDAO.class);
        dispatchWorkflowService = mock(DispatchWorkflowService.class);

        DispatchTemplatesEntity template = new DispatchTemplatesEntity();
        template.setId(TEMPLATE_ID);
        template.setName("編組一");
        when(dispatchTemplatesDAO.findById(TEMPLATE_ID)).thenReturn(Optional.of(template));
        when(templateRoutesDAO.findByTemplateIdOrderByIdAsc(TEMPLATE_ID)).thenReturn(slots);
        when(dispatchWorkflowService.optimizeSlots(any())).thenReturn(new DispatchResponse());

        service = new TemplatesService(dispatchTemplatesDAO, templateRoutesDAO, mock(TemplateStopsDAO.class),
                mock(WarehousesDAO.class), vehiclesDAO, mock(StoresDAO.class), mock(DriversDAO.class),
                dispatchWorkflowService);
    }

    @Test
    void 只填司機的格子也送去排_車留給自動配() {
        slot(1L, 3L, null);

        service.applyToDate(TEMPLATE_ID, DATE);

        OptimizeSlotsDTO sent = sentRequests(1).get(0);
        assertEquals(1, sent.getSlots().size());
        assertEquals(3L, sent.getSlots().get(0).getDriverId());
        assertNull(sent.getSlots().get(0).getVehicleId());
        // 只有人的格子沒有車可查，不能拿 null 去查車
        verify(vehiclesDAO, never()).findById(any());
    }

    @Test
    void 跨倉編組_每倉各排一次_照編組順序() {
        givenVehicle(11L, 1L);
        givenVehicle(21L, 2L);
        slot(1L, 3L, 11L);
        slot(2L, 5L, 21L);
        slot(1L, 4L, null);

        List<DispatchResponse> boards = service.applyToDate(TEMPLATE_ID, DATE);

        List<OptimizeSlotsDTO> sent = sentRequests(2);
        assertEquals(2, boards.size());
        assertEquals(1L, sent.get(0).getWarehouseId());
        assertEquals(2, sent.get(0).getSlots().size());
        assertEquals(2L, sent.get(1).getWarehouseId());
        assertEquals(1, sent.get(1).getSlots().size());
        assertEquals(DATE, sent.get(1).getDate());
    }

    @Test
    void 車輛調到別倉_擋下_一倉都不排() {
        givenVehicle(11L, 2L);
        slot(1L, 3L, 11L);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.applyToDate(TEMPLATE_ID, DATE));

        assertTrue(e.getMessage().contains("已調至其他倉庫"), e.getMessage());
        verify(dispatchWorkflowService, never()).optimizeSlots(any());
    }

    @Test
    void 某一倉被擋_例外往外丟_交給交易回滾前面的倉() {
        givenVehicle(11L, 1L);
        givenVehicle(21L, 2L);
        slot(1L, 3L, 11L);
        slot(2L, 5L, 21L);
        when(dispatchWorkflowService.optimizeSlots(any()))
                .thenReturn(new DispatchResponse())
                .thenThrow(new IllegalArgumentException("當天已有發布的路線，請先撤回再重排"));

        // 單元測試看不到回滾本身；這裡確認例外沒有被吞掉，類別上的 @Transactional 才會回滾
        assertThrows(IllegalArgumentException.class, () -> service.applyToDate(TEMPLATE_ID, DATE));
    }

    private List<OptimizeSlotsDTO> sentRequests(int times) {
        ArgumentCaptor<OptimizeSlotsDTO> captor = ArgumentCaptor.forClass(OptimizeSlotsDTO.class);
        verify(dispatchWorkflowService, times(times)).optimizeSlots(captor.capture());
        return captor.getAllValues();
    }

    private void slot(Long warehouseId, Long driverId, Long vehicleId) {
        TemplateRoutesEntity slot = new TemplateRoutesEntity();
        slot.setId(nextSlotId++);
        slot.setTemplateId(TEMPLATE_ID);
        slot.setWarehouseId(warehouseId);
        slot.setDriverId(driverId);
        slot.setVehicleId(vehicleId);
        slots.add(slot);
    }

    private void givenVehicle(Long id, Long warehouseId) {
        VehiclesEntity vehicle = new VehiclesEntity();
        vehicle.setId(id);
        vehicle.setPlateNumber("TN-" + id);
        vehicle.setWarehouseId(warehouseId);
        when(vehiclesDAO.findById(id)).thenReturn(Optional.of(vehicle));
    }
}
