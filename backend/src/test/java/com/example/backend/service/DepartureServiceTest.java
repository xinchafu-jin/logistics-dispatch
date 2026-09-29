package com.example.backend.service;

import com.example.backend.dto.request.MileageRequestDTO;
import com.example.backend.dto.request.PreTripInspectionRequestDTO;
import com.example.backend.dto.respones.PreTripInspectionResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 出車：檢查通過才接著用原本的出車里程流程記行車紀錄器里程和照片；沒通過就只留檢查紀錄。
 *
 * <p>兩個 service 都用 mock，這裡只測「什麼情況呼叫誰、照什麼順序」。
 * 整筆退回（rollback）是 Spring 交易的行為，由 PreTripInspectionApiTest 經過真的交易測。</p>
 */
class DepartureServiceTest {

    private static final long DRIVER_ID = 1L;
    private static final long ROUTE_ID = 3L;

    private PreTripInspectionService preTripInspectionService;
    private MileageLogsService mileageLogsService;
    private DepartureService service;
    private MultipartFile alcoholPhoto;
    private MultipartFile dashcamPhoto;

    @BeforeEach
    void setUp() {
        preTripInspectionService = mock(PreTripInspectionService.class);
        mileageLogsService = mock(MileageLogsService.class);
        service = new DepartureService(preTripInspectionService, mileageLogsService);
        alcoholPhoto = new MockMultipartFile("alcoholPhoto", "alcohol.jpg", "image/jpeg", new byte[]{1});
        dashcamPhoto = new MockMultipartFile("dashcamPhoto", "dashcam.jpg", "image/jpeg", new byte[]{1});
    }

    @Test
    void 檢查通過_依序記出車里程_存行車紀錄器照片_回傳重查的結果() {
        givenSubmitResult(true);
        PreTripInspectionResponse reloaded = new PreTripInspectionResponse();
        when(preTripInspectionService.findLatest(DRIVER_ID, ROUTE_ID)).thenReturn(reloaded);

        PreTripInspectionResponse response = service.submitPreTripInspection(
                DRIVER_ID, request(18400), alcoholPhoto, dashcamPhoto, null);

        assertSame(reloaded, response);
        InOrder order = inOrder(preTripInspectionService, mileageLogsService);
        order.verify(preTripInspectionService).submit(eq(DRIVER_ID), any(), eq(alcoholPhoto), isNull());
        order.verify(mileageLogsService).start(eq(DRIVER_ID), argThat((MileageRequestDTO mileage) -> mileage.getOdometer() == 18400));
        order.verify(mileageLogsService).attachStartPhoto(DRIVER_ID, dashcamPhoto);
    }

    @Test
    void 檢查沒通過_不出車_只回傳檢查結果() {
        PreTripInspectionResponse failed = givenSubmitResult(false);

        PreTripInspectionResponse response = service.submitPreTripInspection(
                DRIVER_ID, request(18400), alcoholPhoto, dashcamPhoto, null);

        assertSame(failed, response);
        verify(mileageLogsService, never()).start(anyLong(), any());
        verify(mileageLogsService, never()).attachStartPhoto(anyLong(), any());
    }

    @Test
    void 檢查通過卻沒填行車紀錄器里程_丟例外讓整筆退回() {
        givenSubmitResult(true);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.submitPreTripInspection(DRIVER_ID, request(null), alcoholPhoto, dashcamPhoto, null));

        assertEquals("請填出車時的行車紀錄器里程", error.getMessage());
        verify(mileageLogsService, never()).start(anyLong(), any());
    }

    @Test
    void 檢查通過卻沒拍行車紀錄器_丟例外讓整筆退回() {
        givenSubmitResult(true);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.submitPreTripInspection(DRIVER_ID, request(18400), alcoholPhoto, null, null));

        assertEquals("請拍行車紀錄器畫面，要看得到里程", error.getMessage());
        verify(mileageLogsService, never()).start(anyLong(), any());
    }

    @Test
    void 出車失敗_例外照樣往外丟_不會吞掉() {
        givenSubmitResult(true);
        when(mileageLogsService.start(eq(DRIVER_ID), any()))
                .thenThrow(new IllegalArgumentException("請先完成上班打卡，且不可在休息或下班狀態開始出車"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.submitPreTripInspection(DRIVER_ID, request(18400), alcoholPhoto, dashcamPhoto, null));

        assertEquals("請先完成上班打卡，且不可在休息或下班狀態開始出車", error.getMessage());
        verify(mileageLogsService, never()).attachStartPhoto(anyLong(), any());
    }

    private PreTripInspectionResponse givenSubmitResult(boolean passed) {
        PreTripInspectionResponse result = new PreTripInspectionResponse();
        result.setPassed(passed);
        when(preTripInspectionService.submit(eq(DRIVER_ID), any(), any(), any())).thenReturn(result);
        return result;
    }

    private PreTripInspectionRequestDTO request(Integer odometer) {
        PreTripInspectionRequestDTO request = new PreTripInspectionRequestDTO();
        request.setRouteId(ROUTE_ID);
        request.setOdometer(odometer);
        return request;
    }
}
