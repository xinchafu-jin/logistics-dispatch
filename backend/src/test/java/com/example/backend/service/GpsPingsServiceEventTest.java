package com.example.backend.service;

import com.example.backend.dao.DriversDAO;
import com.example.backend.dao.GpsPingsDAO;
import com.example.backend.dto.request.GpsPingDTO;
import com.example.backend.dto.respones.GpsPingSavedEvent;
import com.example.backend.entity.GpsPingsEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * savePing 存好 GPS 後要發出 GpsPingSavedEvent，偏離判斷才接得到；沒存成功就不能發。
 */
class GpsPingsServiceEventTest {

    private static final long DRIVER_ID = 7L;

    private GpsPingsDAO gpsPingsDAO;
    private AttendanceService attendanceService;
    private ApplicationEventPublisher eventPublisher;
    private GpsPingsService gpsPingsService;

    @BeforeEach
    void setUp() {
        gpsPingsDAO = mock(GpsPingsDAO.class);
        DriversDAO driversDAO = mock(DriversDAO.class);
        attendanceService = mock(AttendanceService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        when(driversDAO.existsById(DRIVER_ID)).thenReturn(true);
        when(gpsPingsDAO.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        gpsPingsService = new GpsPingsService(gpsPingsDAO, driversDAO, attendanceService, eventPublisher, 10, 5);
    }

    @Test
    void 存好GPS後發出事件_位置與時間跟存進去的一樣() {
        when(attendanceService.isGpsUploadAllowed(DRIVER_ID)).thenReturn(true);

        gpsPingsService.savePing(DRIVER_ID, ping(22.6273, 120.3014));

        ArgumentCaptor<GpsPingsEntity> saved = ArgumentCaptor.forClass(GpsPingsEntity.class);
        verify(gpsPingsDAO).save(saved.capture());
        ArgumentCaptor<GpsPingSavedEvent> event = ArgumentCaptor.forClass(GpsPingSavedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertEquals(DRIVER_ID, event.getValue().getDriverId());
        assertEquals(22.6273, event.getValue().getLat());
        assertEquals(120.3014, event.getValue().getLng());
        // 偏離紀錄的時間要跟 gps_pings 那一筆一致（伺服器時間），不是另外再取一次 now
        assertEquals(saved.getValue().getTimestamp(), event.getValue().getTimestamp());
    }

    @Test
    void 休息中被拒絕_沒存就不發事件() {
        when(attendanceService.isGpsUploadAllowed(DRIVER_ID)).thenReturn(false);

        assertThrows(IllegalArgumentException.class, () -> gpsPingsService.savePing(DRIVER_ID, ping(22.6273, 120.3014)));

        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    private GpsPingDTO ping(double lat, double lng) {
        GpsPingDTO dto = new GpsPingDTO();
        dto.setLat(lat);
        dto.setLng(lng);
        return dto;
    }
}
