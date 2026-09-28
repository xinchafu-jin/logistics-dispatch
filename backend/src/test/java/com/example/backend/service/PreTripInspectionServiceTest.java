package com.example.backend.service;

import com.example.backend.constants.RouteStatus;
import com.example.backend.dao.DriversDAO;
import com.example.backend.dao.MileageLogsDAO;
import com.example.backend.dao.PreTripInspectionsDAO;
import com.example.backend.dao.RoutesDAO;
import com.example.backend.dto.request.PreTripInspectionRequestDTO;
import com.example.backend.dto.respones.PreTripInspectionResponse;
import com.example.backend.entity.DriversEntity;
import com.example.backend.entity.MileageLogsEntity;
import com.example.backend.entity.PreTripInspectionsEntity;
import com.example.backend.entity.RoutesEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 出車前安全檢查的規則：什麼算通過、不通過怎麼存、什麼時候擋出車與點交、撤回時怎麼處理。
 *
 * <p>DAO、照片存檔全部用 mock。司機 1 號今天有一條已發布的路線 3，開車 2，路線版本 1。</p>
 */
class PreTripInspectionServiceTest {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final long DRIVER_ID = 1L;
    private static final long VEHICLE_ID = 2L;
    private static final long ROUTE_ID = 3L;

    private PreTripInspectionsDAO preTripInspectionsDAO;
    private RoutesDAO routesDAO;
    private MileageLogsDAO mileageLogsDAO;
    private PreTripPhotoStorageService photoStorageService;
    private PreTripInspectionService service;
    private RoutesEntity route;

    @BeforeEach
    void setUp() {
        preTripInspectionsDAO = mock(PreTripInspectionsDAO.class);
        routesDAO = mock(RoutesDAO.class);
        mileageLogsDAO = mock(MileageLogsDAO.class);
        photoStorageService = mock(PreTripPhotoStorageService.class);
        DriversDAO driversDAO = mock(DriversDAO.class);

        DriversEntity driver = new DriversEntity();
        driver.setId(DRIVER_ID);
        driver.setName("王小明");
        driver.setIsActive(true);
        when(driversDAO.findById(DRIVER_ID)).thenReturn(Optional.of(driver));

        route = new RoutesEntity();
        route.setId(ROUTE_ID);
        route.setDriverId(DRIVER_ID);
        route.setVehicleId(VEHICLE_ID);
        route.setStatus(RouteStatus.PUBLISHED);
        route.setDate(LocalDate.now(TAIPEI));
        when(routesDAO.findForUpdate(ROUTE_ID)).thenReturn(Optional.of(route));
        when(routesDAO.findById(ROUTE_ID)).thenReturn(Optional.of(route));

        when(photoStorageService.store(any(), eq("酒測器讀數"))).thenReturn("alcohol.jpg");
        when(photoStorageService.store(any(), eq("故障"))).thenReturn("fault.jpg");
        when(preTripInspectionsDAO.saveAndFlush(any(PreTripInspectionsEntity.class))).thenAnswer(invocation -> {
            PreTripInspectionsEntity saved = invocation.getArgument(0);
            saved.setId(40L);
            return saved;
        });

        service = new PreTripInspectionService(preTripInspectionsDAO, routesDAO, driversDAO, mileageLogsDAO,
                photoStorageService);
    }

    // ── 出車、點交前的放行 ────────────────────────────────

    @Test
    void 還沒檢查_不能出車也不能點交() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.requirePassed(DRIVER_ID, ROUTE_ID));

        assertEquals("請先在「今日任務」完成出車前安全檢查", error.getMessage());
    }

    @Test
    void 最新一筆沒通過_不能出車() {
        givenLatest(inspection(false));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.requirePassed(DRIVER_ID, ROUTE_ID));

        assertEquals("出車前安全檢查沒有通過，請聯絡主管處理", error.getMessage());
    }

    @Test
    void 最新一筆通過_放行並鎖住路線() {
        givenLatest(inspection(true));

        assertSame(route, service.requirePassed(DRIVER_ID, ROUTE_ID));
        // 要用鎖住的查詢：跟撤回搶同一把鎖
        verify(routesDAO).findForUpdate(ROUTE_ID);
    }

    @Test
    void 換了車_舊車的通過紀錄不算() {
        givenLatest(inspection(true));
        // 主管把這條路線換成 9 號車：查詢帶的是新車，舊車那筆查不到
        route.setVehicleId(9L);

        assertThrows(IllegalArgumentException.class, () -> service.requirePassed(DRIVER_ID, ROUTE_ID));
    }

    @Test
    void 別人的_已撤回的_不是今天的路線都不能檢查() {
        route.setDriverId(8L);
        assertThrows(IllegalArgumentException.class, () -> service.findLatest(DRIVER_ID, ROUTE_ID));

        route.setDriverId(DRIVER_ID);
        route.setStatus(RouteStatus.DRAFT);
        assertThrows(IllegalArgumentException.class, () -> service.requirePassed(DRIVER_ID, ROUTE_ID));

        route.setStatus(RouteStatus.PUBLISHED);
        route.setDate(LocalDate.now(TAIPEI).minusDays(1));
        assertThrows(IllegalArgumentException.class, () -> service.findLatest(DRIVER_ID, ROUTE_ID));
    }

    // ── 送出檢查 ──────────────────────────────────────────

    @Test
    void 酒測0且15項全部正常_通過_人車版本取自路線() {
        PreTripInspectionResponse response = service.submit(DRIVER_ID, request("0.00"), photo(), null);

        assertTrue(response.isPassed());
        assertTrue(response.isCompleted());
        assertTrue(response.getAbnormalItems().isEmpty());
        // 出車里程由 DepartureService 接著記，這一步還沒出車
        assertFalse(response.isDeparted());
        verify(preTripInspectionsDAO).saveAndFlush(argThat(saved -> saved.getPassed()
                && saved.getDriverId() == DRIVER_ID
                && saved.getVehicleId() == VEHICLE_ID
                && saved.getRouteVersion() == 1
                && "alcohol.jpg".equals(saved.getAlcoholPhoto())
                && saved.getFaultPhoto() == null));
    }

    @Test
    void 酒測不是0_照樣存下但不通過() {
        PreTripInspectionResponse response = service.submit(DRIVER_ID, request("0.01"), photo(), null);

        assertFalse(response.isPassed());
        assertEquals("酒測不是 0.00，不能出車，請聯絡主管", response.getMessage());
        verify(preTripInspectionsDAO).saveAndFlush(argThat(saved -> !saved.getPassed()
                && saved.getAlcoholMgL().compareTo(new BigDecimal("0.01")) == 0));
    }

    @Test
    void 有異常項目_照樣存下但不通過_列出是哪幾項() {
        PreTripInspectionRequestDTO request = request("0.00");
        request.setBrakeLights(false);
        request.setTireTread(false);
        request.setNote("左後煞車燈不亮，右後輪胎紋快磨平");

        PreTripInspectionResponse response = service.submit(DRIVER_ID, request, photo(), photo());

        assertFalse(response.isPassed());
        // 順序跟司機端畫面一樣：二胎在四燈前面
        assertEquals(List.of("胎紋", "煞車燈"), response.getAbnormalItems());
        assertTrue(response.isHasFaultPhoto());
        verify(preTripInspectionsDAO).saveAndFlush(argThat(saved -> !saved.getPassed()
                && "fault.jpg".equals(saved.getFaultPhoto())
                && "左後煞車燈不亮，右後輪胎紋快磨平".equals(saved.getNote())));
    }

    @Test
    void 有異常卻沒寫說明_不能送出_也不存照片() {
        PreTripInspectionRequestDTO request = request("0.00");
        request.setCoolant(false);
        request.setNote("   ");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.submit(DRIVER_ID, request, photo(), null));

        assertEquals("有異常項目，請說明狀況", error.getMessage());
        verify(photoStorageService, never()).store(any(), any());
        verify(preTripInspectionsDAO, never()).saveAndFlush(any());
    }

    @Test
    void 已經出車_不能再送_免得一筆不通過把點交擋掉() {
        givenLatest(inspection(true));
        givenDeparted(18400);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.submit(DRIVER_ID, request("0.05"), photo(), null));

        assertEquals("今天已經出車，不用再做安全檢查", error.getMessage());
        verify(preTripInspectionsDAO, never()).saveAndFlush(any());
    }

    @Test
    void 沒通過可以重送() {
        givenLatest(inspection(false));

        PreTripInspectionResponse response = service.submit(DRIVER_ID, request("0.00"), photo(), null);

        assertTrue(response.isPassed());
    }

    @Test
    void 通過但還沒出車_可以再送一次補記里程() {
        // 改版前通過、當時出車里程要另外記的
        givenLatest(inspection(true));

        assertEquals("檢查通過，但還沒記出車時的行車紀錄器里程，請再送一次檢查",
                service.findLatest(DRIVER_ID, ROUTE_ID).getMessage());
        assertTrue(service.submit(DRIVER_ID, request("0.00"), photo(), null).isPassed());
    }

    @Test
    void 通過而且出車了_回傳出車時的行車紀錄器里程() {
        givenLatest(inspection(true));
        givenDeparted(18400);

        PreTripInspectionResponse response = service.findLatest(DRIVER_ID, ROUTE_ID);

        assertTrue(response.isDeparted());
        assertEquals(18400, response.getStartOdometer());
        assertEquals("檢查通過，已記下出車時的行車紀錄器里程 18400 km，可以點交", response.getMessage());
    }

    @Test
    void 第二張照片存失敗_第一張要刪掉_不留沒有紀錄的檔案() {
        when(photoStorageService.store(any(), eq("故障"))).thenThrow(new IllegalArgumentException("故障照片只支援 JPG、PNG 或 WebP"));
        PreTripInspectionRequestDTO request = request("0.00");
        request.setFuel(false);
        request.setNote("油量不到四分之一");

        assertThrows(IllegalArgumentException.class, () -> service.submit(DRIVER_ID, request, photo(), photo()));

        verify(photoStorageService).discard("alcohol.jpg");
        verify(preTripInspectionsDAO, never()).saveAndFlush(any());
    }

    // ── 撤回 ──────────────────────────────────────────────

    @Test
    void 撤回_有司機已經記了出車里程_整批擋下() {
        when(routesDAO.findByDate(route.getDate())).thenReturn(List.of(route));
        when(mileageLogsDAO.findAllByRouteIdOrderByStartTimeAsc(ROUTE_ID)).thenReturn(List.of(new MileageLogsEntity()));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.prepareWithdraw(route.getDate()));

        assertEquals("已有司機記了出車里程，不能撤回（王小明）", error.getMessage());
        verify(preTripInspectionsDAO, never()).saveAll(any());
    }

    @Test
    void 撤回_沒人出車_當天的檢查全部作廢() {
        PreTripInspectionsEntity passed = inspection(true);
        when(routesDAO.findByDate(route.getDate())).thenReturn(List.of(route));
        when(preTripInspectionsDAO.findAllByRouteIdAndInvalidatedAtIsNull(ROUTE_ID)).thenReturn(List.of(passed));

        service.prepareWithdraw(route.getDate());

        assertNotNull(passed.getInvalidatedAt());
    }

    @Test
    void 撤回_草稿路線不用鎖也不用處理() {
        route.setStatus(RouteStatus.DRAFT);
        when(routesDAO.findByDate(route.getDate())).thenReturn(List.of(route));

        service.prepareWithdraw(route.getDate());

        verify(routesDAO, never()).findForUpdate(ROUTE_ID);
    }

    // ── 照片 ──────────────────────────────────────────────

    @Test
    void 不能看別人的檢查照片() {
        PreTripInspectionsEntity others = inspection(true);
        others.setDriverId(8L);
        when(preTripInspectionsDAO.findById(40L)).thenReturn(Optional.of(others));

        assertThrows(IllegalArgumentException.class, () -> service.photo(DRIVER_ID, 40L, "alcohol"));
        verify(photoStorageService, never()).resolve(any());
    }

    @Test
    void 查詢時還沒送過_回傳未完成() {
        PreTripInspectionResponse response = service.findLatest(DRIVER_ID, ROUTE_ID);

        assertFalse(response.isCompleted());
        assertFalse(response.isPassed());
        assertNull(response.getId());
        assertEquals(VEHICLE_ID, response.getVehicleId());
    }

    private void givenLatest(PreTripInspectionsEntity inspection) {
        when(preTripInspectionsDAO
                .findFirstByRouteIdAndDriverIdAndVehicleIdAndRouteVersionAndInvalidatedAtIsNullOrderByIdDesc(
                        ROUTE_ID, DRIVER_ID, VEHICLE_ID, 1))
                .thenReturn(Optional.of(inspection));
    }

    private void givenDeparted(int startOdometer) {
        MileageLogsEntity mileage = new MileageLogsEntity();
        mileage.setDriverId(DRIVER_ID);
        mileage.setRouteId(ROUTE_ID);
        mileage.setStartOdometer(startOdometer);
        when(mileageLogsDAO.findByDriverIdAndDate(DRIVER_ID, LocalDate.now(TAIPEI))).thenReturn(Optional.of(mileage));
    }

    private PreTripInspectionsEntity inspection(boolean passed) {
        PreTripInspectionsEntity inspection = new PreTripInspectionsEntity();
        inspection.setId(40L);
        inspection.setRouteId(ROUTE_ID);
        inspection.setDriverId(DRIVER_ID);
        inspection.setVehicleId(VEHICLE_ID);
        inspection.setRouteVersion(1);
        inspection.setWorkDate(LocalDate.now(TAIPEI));
        inspection.setPassed(passed);
        inspection.setAlcoholMgL(BigDecimal.ZERO);
        inspection.setAlcoholPhoto("alcohol.jpg");
        return inspection;
    }

    /** 15 項全部正常的請求，個別測試再把某幾項改成異常 */
    private PreTripInspectionRequestDTO request(String alcoholMgL) {
        PreTripInspectionRequestDTO request = new PreTripInspectionRequestDTO();
        request.setRouteId(ROUTE_ID);
        request.setAlcoholMgL(new BigDecimal(alcoholMgL));
        request.setDashcam(true);
        request.setEngineOil(true);
        request.setBrakeFluid(true);
        request.setPowerSteeringFluid(true);
        request.setTransmissionOil(true);
        request.setFuel(true);
        request.setCoolant(true);
        request.setBatteryWater(true);
        request.setWasherFluid(true);
        request.setTirePressure(true);
        request.setTireTread(true);
        request.setHeadlights(true);
        request.setTurnSignals(true);
        request.setBrakeLights(true);
        request.setDashboardLights(true);
        return request;
    }

    private MockMultipartFile photo() {
        return new MockMultipartFile("file", "photo.jpg", "image/jpeg", new byte[]{1});
    }
}
