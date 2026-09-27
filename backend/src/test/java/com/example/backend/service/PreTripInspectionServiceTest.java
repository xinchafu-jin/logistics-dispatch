package com.example.backend.service;

import com.example.backend.constants.RouteStatus;
import com.example.backend.dao.*;
import com.example.backend.dto.request.PreTripInspectionRequest;
import com.example.backend.entity.*;
import jakarta.validation.Validation;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PreTripInspectionServiceTest {
    PreTripInspectionDAO dao; RoutesDAO routes; MileageLogsDAO mileage; PreTripPhotoStorageService photos;
    PreTripInspectionService service; RoutesEntity route;
    @BeforeEach void setup() {
        dao = mock(PreTripInspectionDAO.class); routes = mock(RoutesDAO.class); mileage = mock(MileageLogsDAO.class);
        photos = mock(PreTripPhotoStorageService.class); DriversDAO drivers = mock(DriversDAO.class);
        DriversEntity driver = new DriversEntity(); driver.setIsActive(true); when(drivers.findById(1L)).thenReturn(Optional.of(driver));
        route = new RoutesEntity(); route.setId(3L); route.setDriverId(1L); route.setVehicleId(2L); route.setStatus(RouteStatus.PUBLISHED);
        route.setDate(LocalDate.now(ZoneId.of("Asia/Taipei")));
        when(routes.findForUpdate(3L)).thenReturn(Optional.of(route)); when(routes.findById(3L)).thenReturn(Optional.of(route));
        service = new PreTripInspectionService(dao, routes, drivers, mileage, photos);
    }
    @AfterEach void cleanup() { if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.clearSynchronization(); }
    PreTripInspectionRequest request(String concentration) {
        return new PreTripInspectionRequest(3L, new BigDecimal(concentration), true,true,true,true,true,true,true,true,true,true);
    }
    @Test void 未檢查不能點交或出車() { assertThrows(IllegalArgumentException.class, () -> service.requirePassed(1L,3L)); }
    @Test void 當次人車版本通過才放行() {
        PreTripInspection record = new PreTripInspection(); record.passed = true;
        when(dao.findFirstByRouteIdAndDriverIdAndVehicleIdAndRouteVersionAndInvalidatedAtIsNullOrderByIdDesc(3L,1L,2L,1)).thenReturn(Optional.of(record));
        assertSame(route, service.requirePassed(1L,3L));
        route.setVehicleId(9L); assertThrows(IllegalArgumentException.class, () -> service.requirePassed(1L,3L));
    }
    @Test void 不能操作別人或非今日或已撤回任務() {
        route.setDriverId(8L); assertThrows(IllegalArgumentException.class, () -> service.find(1L,3L));
        route.setDriverId(1L); route.setDate(route.getDate().minusDays(1)); assertThrows(IllegalArgumentException.class, () -> service.find(1L,3L));
        route.setDate(LocalDate.now(ZoneId.of("Asia/Taipei"))); route.setStatus(RouteStatus.DRAFT);
        assertThrows(IllegalArgumentException.class, () -> service.requirePassed(1L,3L));
    }
    @Test void 零濃度三張照片綁定任務並保存() {
        var result = submit("0.00"); assertTrue(result.passed()); assertTrue(result.completed());
        verify(photos,times(3)).store(any());
        verify(dao).saveAndFlush(argThat(record -> record.driverId == 1L && record.vehicleId == 2L
                && record.routeVersion == 1 && record.dashcam && record.frontLeftTire && record.alcoholMgL.signum() == 0));
    }
    @Test void 非零酒測留下不通過紀錄且不能放行() {
        var result = submit("0.01"); assertFalse(result.passed()); assertTrue(result.completed());
        verify(dao).saveAndFlush(argThat(record -> !record.passed && record.alcoholMgL.compareTo(new BigDecimal("0.01")) == 0));
    }
    PreTripInspectionService.Result submit(String value) {
        TransactionSynchronizationManager.initSynchronization();
        when(photos.store(any())).thenReturn("a.jpg","b.jpg","c.jpg");
        when(dao.saveAndFlush(any())).thenAnswer(call -> { PreTripInspection r = call.getArgument(0); r.id=4L; return r; });
        var photo = new MockMultipartFile("file", "a.jpg", "image/jpeg", new byte[]{1});
        return service.submit(1L, request(value), photo,photo,photo);
    }
    @Test void 第二張照片失敗不留半套檔案() {
        when(photos.store(any())).thenReturn("a.jpg").thenThrow(new IllegalArgumentException("bad photo"));
        var photo = new MockMultipartFile("file",new byte[]{1});
        assertThrows(IllegalArgumentException.class, () -> service.submit(1L,request("0"),photo,photo,photo));
        verify(photos).discard("a.jpg"); verify(dao,never()).saveAndFlush(any());
    }
    @Test void 完成檢查仍能撤回且所有紀錄作廢() {
        when(routes.findByDate(route.getDate())).thenReturn(List.of(route));
        PreTripInspection record = new PreTripInspection(); when(dao.findByRouteIdAndInvalidatedAtIsNull(3L)).thenReturn(List.of(record));
        service.prepareWithdraw(route.getDate()); assertNotNull(record.invalidatedAt);
    }
    @Test void 已起登里程不能撤回() {
        when(routes.findByDate(route.getDate())).thenReturn(List.of(route));
        when(mileage.findAllByRouteIdOrderByStartTimeAsc(3L)).thenReturn(List.of(new MileageLogsEntity()));
        assertThrows(IllegalArgumentException.class, () -> service.prepareWithdraw(route.getDate()));
    }
    @Test void 不能看別人的酒測照片() {
        PreTripInspection record = new PreTripInspection(); record.driverId=8L; when(dao.findById(4L)).thenReturn(Optional.of(record));
        assertThrows(IllegalArgumentException.class, () -> service.photo(1L,4L,"alcohol")); verifyNoInteractions(photos);
    }
    @Test void 所有勾選與濃度必填且不能負值或精度超限() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator(); assertTrue(validator.validate(request("0.00")).isEmpty());
            assertFalse(validator.validate(request("-0.01")).isEmpty()); assertFalse(validator.validate(request("0.001")).isEmpty());
            var missing = new PreTripInspectionRequest(3L,null,false,false,false,false,false,false,false,false,false,false);
            assertEquals(11,validator.validate(missing).size());
        }
    }
}
