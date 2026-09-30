package com.example.backend.service;

import com.example.backend.dao.*;
import com.example.backend.entity.*;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ReportPreTripServiceTest {
    private final LocalDate day = LocalDate.now(ZoneId.of("Asia/Taipei")).minusDays(1);
    private final ReportService.Range range = new ReportService.Range(day, day);
    private final ReportReadDAO reads = mock(ReportReadDAO.class);
    private final PreTripInspectionsDAO inspections = mock(PreTripInspectionsDAO.class);
    private final RoutesDAO routes = mock(RoutesDAO.class);
    private final DriversDAO drivers = mock(DriversDAO.class);
    private final VehiclesDAO vehicles = mock(VehiclesDAO.class);
    private final WarehousesDAO warehouses = mock(WarehousesDAO.class);
    private final OrdersDAO orders = mock(OrdersDAO.class);
    private final PreTripPhotoStorageService photos = mock(PreTripPhotoStorageService.class);
    private final ReportPreTripService service = new ReportPreTripService(reads, inspections,
            routes, drivers, vehicles, warehouses, orders, photos);

    @BeforeEach
    void directories() {
        RoutesEntity route = new RoutesEntity();
        route.setId(10L); route.setWarehouseId(1L); route.setVehicleId(99L);
        DriversEntity driver = new DriversEntity();
        driver.setId(2L); driver.setName("陳柏宇"); driver.setAccount("DRV001");
        VehiclesEntity vehicle = new VehiclesEntity();
        vehicle.setId(3L); vehicle.setPlateNumber("KAE-2081");
        WarehousesEntity warehouse = new WarehousesEntity();
        warehouse.setId(1L); warehouse.setName("高雄左營倉");
        when(routes.findAllById(any())).thenReturn(List.of(route));
        when(drivers.findAllById(any())).thenReturn(List.of(driver));
        when(vehicles.findAllById(any())).thenReturn(List.of(vehicle));
        when(warehouses.findAllById(any())).thenReturn(List.of(warehouse));
    }

    @Test
    void keepsEveryAttemptIncludingFailedWithdrawnAndDeletedRoutes() {
        var failed = inspection(1L);
        failed.setPassed(false); failed.setBrakeLights(false); failed.setCoolant(null);
        failed.setAlcoholMgL(new BigDecimal("0.12")); failed.setNote("煞車燈待修");
        failed.setInvalidatedAt(day.atTime(11, 0));
        var retried = inspection(2L);
        retried.setSubmittedAt(day.atTime(10, 0));
        var old = inspection(3L); old.setRouteId(888L); old.setDriverId(888L); old.setVehicleId(888L);
        when(reads.inspections(day, day)).thenReturn(List.of(failed, retried, old));

        var rows = service.history(range, null, null, null, null).inspections();

        assertEquals(3, rows.size());
        assertEquals(2L, rows.getFirst().inspectionId());
        var failedRow = rows.stream().filter(i -> i.inspectionId() == 1L).findFirst().orElseThrow();
        assertEquals(15, failedRow.checks().size());
        assertEquals(List.of("酒測", "煞車燈"), failedRow.abnormalItems());
        assertNull(failedRow.checks().stream().filter(c -> c.key().equals("coolant")).findFirst().orElseThrow().normal());
        assertEquals(failed.getInvalidatedAt(), failedRow.invalidatedAt());
        assertEquals("煞車燈待修", failedRow.note());
        var oldRow = rows.stream().filter(i -> i.inspectionId() == 3L).findFirst().orElseThrow();
        assertEquals(888L, oldRow.routeId());
        assertNull(oldRow.warehouseId());
        assertNull(oldRow.driverName());
        assertNull(oldRow.plateNumber());
        verify(inspections, never()).save(any());
    }

    @Test
    void filtersUsingRecordedDriverAndVehicleEvenAfterRouteReassignment() {
        var first = inspection(1L);
        var other = inspection(2L); other.setVehicleId(4L);
        when(reads.inspections(day, day)).thenReturn(List.of(first, other));
        OrdersEntity order = new OrdersEntity(); order.setRouteId(10L); order.setStoreId(5L);
        when(orders.findByRouteIdIn(any())).thenReturn(List.of(order));

        var result = service.history(range, 1L, 5L, 2L, Set.of(3L));

        assertEquals(List.of(1L), result.inspections().stream().map(i -> i.inspectionId()).toList());
        assertEquals("KAE-2081", result.inspections().getFirst().plateNumber());
        assertTrue(service.history(range, 9L, null, null, null).inspections().isEmpty());
        assertTrue(service.history(range, null, 9L, null, null).inspections().isEmpty());
        assertTrue(service.history(range, null, null, 9L, null).inspections().isEmpty());
    }

    @Test
    void excludesFutureDatedOrFutureSubmittedHistory() {
        var futureDay = inspection(1L); futureDay.setWorkDate(day.plusDays(3));
        var futureTime = inspection(2L); futureTime.setSubmittedAt(day.plusDays(3).atTime(8, 0));
        when(reads.inspections(day, day)).thenReturn(List.of(futureDay, futureTime));
        assertTrue(service.history(range, null, null, null, null).inspections().isEmpty());
        clearInvocations(reads);
        var futureRange = new ReportService.Range(day.plusDays(3), day.plusDays(3));
        assertTrue(service.history(futureRange, null, null, null, null).inspections().isEmpty());
        assertTrue(service.history(range, null, null, null, Set.of()).inspections().isEmpty());
        verifyNoInteractions(reads);
    }

    @Test
    void resolvesOnlyStoredPhotoNamesAndRejectsMissingPhotos() {
        var inspection = inspection(1L); inspection.setAlcoholPhoto("stored-photo.jpg");
        when(inspections.findById(1L)).thenReturn(Optional.of(inspection));
        Path photo = Path.of("build", "stored-photo.jpg");
        when(photos.resolve("stored-photo.jpg")).thenReturn(photo);
        assertEquals(photo, service.photo(1L, "alcohol"));
        assertThrows(EntityNotFoundException.class, () -> service.photo(1L, "fault"));
        assertThrows(IllegalArgumentException.class, () -> service.photo(1L, "../alcohol"));
    }

    private PreTripInspectionsEntity inspection(long id) {
        var i = new PreTripInspectionsEntity();
        i.setId(id); i.setWorkDate(day); i.setSubmittedAt(day.atTime(8, 0));
        i.setDriverId(2L); i.setVehicleId(3L); i.setRouteId(10L); i.setRouteVersion(1);
        i.setAlcoholMgL(BigDecimal.ZERO); i.setPassed(true);
        i.setDashcam(true); i.setEngineOil(true); i.setBrakeFluid(true); i.setPowerSteeringFluid(true);
        i.setTransmissionOil(true); i.setFuel(true); i.setCoolant(true); i.setBatteryWater(true);
        i.setWasherFluid(true); i.setTirePressure(true); i.setTireTread(true); i.setHeadlights(true);
        i.setTurnSignals(true); i.setBrakeLights(true); i.setDashboardLights(true);
        return i;
    }
}
