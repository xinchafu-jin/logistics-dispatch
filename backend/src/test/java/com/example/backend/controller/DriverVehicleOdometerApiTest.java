package com.example.backend.controller;

import com.example.backend.dao.VehiclesDAO;
import com.example.backend.service.*;
import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManager;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import java.time.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 驗證司機實際儀表數 → DB → 資源里程／保養計算；測試結束 rollback，不改使用者車輛。 */
@SpringBootTest(properties = {"app.crypto.password=test-only-password-test-only-password", "app.crypto.salt=0123456789abcdef"})
@Transactional
class DriverVehicleOdometerApiTest {
    @Autowired WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") Filter security;
    @Autowired JwtEncoder jwt;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @Autowired VehiclesDAO vehicles;
    @MockitoBean AttendanceService attendance;
    @MockitoBean WarehouseProximityService proximity;
    @MockitoBean RouteLegMileageService legs;
    @MockitoBean VehicleMileageSettlementService gpsSettlement;
    @MockitoBean RoutePlanMetricsService metrics;
    @MockitoBean EmergencyLeaveService emergency;
    @MockitoBean PreTripInspectionService preTrip;
    MockMvc mvc;
    long vehicleId, driverId, routeId, warehouseId;
    String adminToken, driverToken;

    @BeforeEach void setup() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(security).build();
        warehouseId = jdbc.queryForObject("SELECT MIN(id) FROM warehouses", Long.class);
        adminToken = bearer(1L, AuthService.ROLE_ADMIN);
        mvc.perform(put("/api/vehicle-maintenance/rules").header("Authorization", adminToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"warningKm\":500,\"policies\":[{\"tonnage\":99.98,\"minorIntervalKm\":3000,\"majorIntervalKm\":20000,\"retirementKm\":500000}]}"))
                .andExpect(status().isOk());
        String response = mvc.perform(post("/api/vehicles").header("Authorization", adminToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"warehouseId\":" + warehouseId + ",\"plateNumber\":\"ODO-TEST\",\"capacity\":50,\"status\":\"AVAILABLE\",\"tonnage\":99.98,\"currentOdometerKm\":0}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        Number id = JsonPath.read(response, "$.id"); vehicleId = id.longValue();
        jdbc.update("INSERT INTO drivers(account,name,work_start,work_end,rest_duration,is_active) VALUES('ODO-DRIVER','里程測試','08:00','17:00',60,1)");
        driverId = jdbc.queryForObject("SELECT id FROM drivers WHERE account='ODO-DRIVER'", Long.class);
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Taipei"));
        jdbc.update("INSERT INTO routes(date,warehouse_id,vehicle_id,driver_id,status,version) VALUES(?,?,?,?,'PUBLISHED',1)", today, warehouseId, vehicleId, driverId);
        routeId = jdbc.queryForObject("SELECT id FROM routes WHERE vehicle_id=?", Long.class, vehicleId);
        driverToken = bearer(driverId, AuthService.ROLE_DRIVER);
        when(attendance.isGpsUploadAllowed(driverId)).thenReturn(true);
        when(preTrip.requirePassed(driverId, routeId)).thenAnswer(invocation -> em.find(com.example.backend.entity.RoutesEntity.class, routeId));
    }

    private void drive(int start, int end) throws Exception {
        mvc.perform(post("/api/driver/mileage/start").header("Authorization", driverToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"odometer\":" + start + "}")).andExpect(status().isOk());
        mvc.perform(post("/api/driver/mileage/end").header("Authorization", driverToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"odometer\":" + end + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.actualDistance").value(end - start))
                .andExpect(jsonPath("$.vehicleCurrentOdometerKm").value(end));
    }

    @Test void newVehicleZeroThenDriverFiftyUpdatesAllMaintenanceRemaining() throws Exception {
        drive(0, 50);
        em.flush(); em.clear();
        mvc.perform(get("/api/vehicles/" + vehicleId).header("Authorization", adminToken))
                .andExpect(jsonPath("$.currentOdometerKm").value(50)).andExpect(jsonPath("$.maintenance.currentOdometerKm").value(50))
                .andExpect(jsonPath("$.maintenance.minorRemainingKm").value(2950))
                .andExpect(jsonPath("$.maintenance.majorRemainingKm").value(19950))
                .andExpect(jsonPath("$.maintenance.retirementRemainingKm").value(499950));
        // GPS 未結算仍必須更新實際總里程；重複收車不得重複加 50。
        mvc.perform(post("/api/driver/mileage/end").header("Authorization", driverToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"odometer\":50}")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/vehicles/" + vehicleId).header("Authorization", adminToken)).andExpect(jsonPath("$.currentOdometerKm").value(50));
    }

    @Test void driverTripDeltaDoesNotAddWholeEndReadingAgain() throws Exception {
        drive(0, 50);
        em.flush(); em.clear();
        LocalDate yesterday = LocalDate.now(ZoneId.of("Asia/Taipei")).minusDays(1);
        jdbc.update("UPDATE mileage_logs SET date=? WHERE vehicle_id=?", yesterday, vehicleId);
        drive(50, 120);
        em.flush(); em.clear();
        mvc.perform(get("/api/vehicles/" + vehicleId).header("Authorization", adminToken))
                .andExpect(jsonPath("$.currentOdometerKm").value(120)).andExpect(jsonPath("$.maintenance.minorRemainingKm").value(2880));
    }

    @Test void completedMaintenanceReadsDriverOdometerWithoutSupervisorNumbers() throws Exception {
        drive(0, 50);
        String body = "{\"warehouseId\":" + warehouseId + ",\"plateNumber\":\"ODO-TEST\",\"capacity\":50,\"tonnage\":99.98,\"status\":\"%s\"}";
        mvc.perform(put("/api/vehicles/" + vehicleId).header("Authorization", adminToken).contentType(MediaType.APPLICATION_JSON)
                .content(body.formatted("MINOR_MAINTENANCE"))).andExpect(status().isOk());
        mvc.perform(put("/api/vehicles/" + vehicleId).header("Authorization", adminToken).contentType(MediaType.APPLICATION_JSON)
                .content(body.formatted("AVAILABLE"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.lastMinorMaintenanceKm").value(50)).andExpect(jsonPath("$.lastMajorMaintenanceKm").value(0))
                .andExpect(jsonPath("$.maintenance.minorRemainingKm").value(3000)).andExpect(jsonPath("$.maintenance.minorCount").value(1));
    }

    @Test void missingCacheIsRecoveredFromLinkedDriverHistoryNotManualEntry() throws Exception {
        drive(0, 50);
        em.flush(); em.clear();
        jdbc.update("UPDATE vehicles SET current_odometer_km=NULL WHERE id=?", vehicleId);
        mvc.perform(get("/api/vehicles/" + vehicleId).header("Authorization", adminToken))
                .andExpect(jsonPath("$.currentOdometerKm").value(50)).andExpect(jsonPath("$.maintenance.minorRemainingKm").value(2950));
        String body = "{\"warehouseId\":" + warehouseId + ",\"plateNumber\":\"ODO-TEST\",\"capacity\":50,\"tonnage\":99.98,\"status\":\"AVAILABLE\"}";
        mvc.perform(put("/api/vehicles/" + vehicleId).header("Authorization", adminToken).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.currentOdometerKm").value(50));
    }

    @Test void missingMaintenanceCacheIsReadFromCompletedRecord() throws Exception {
        completedMaintenanceReadsDriverOdometerWithoutSupervisorNumbers();
        em.flush(); em.clear();
        jdbc.update("UPDATE vehicles SET last_minor_maintenance_km=NULL WHERE id=?", vehicleId);
        mvc.perform(get("/api/vehicles/" + vehicleId).header("Authorization", adminToken))
                .andExpect(jsonPath("$.lastMinorMaintenanceKm").value(50)).andExpect(jsonPath("$.maintenance.minorRemainingKm").value(3000));
    }

    private String bearer(long id, String role) {
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer(AuthService.TOKEN_ISSUER).subject("odometer-api-test")
                .issuedAt(now).expiresAt(now.plusSeconds(300)).claim("userId", id).claim("name", "測試").claim("role", role).build();
        return "Bearer " + jwt.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }
}
