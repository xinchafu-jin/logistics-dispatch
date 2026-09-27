package com.example.backend.controller;

import com.example.backend.service.AuthService;
import com.example.backend.service.VehicleMaintenanceService;
import com.example.backend.dao.VehiclesDAO;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 真 MySQL + 真 API；每個案例完整 rollback，不留下假車、假規則、假保養。 */
@SpringBootTest(properties = {"app.crypto.password=test-only-password-test-only-password", "app.crypto.salt=0123456789abcdef"})
@Transactional
class VehicleMaintenanceApiTest {
    @Autowired WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") Filter security;
    @Autowired JwtEncoder jwt;
    @Autowired JdbcTemplate jdbc;
    @Autowired VehiclesDAO vehicles;
    @Autowired VehicleMaintenanceService maintenance;
    MockMvc mvc;
    long vehicleId, warehouseId;
    String token;

    @BeforeEach void setup() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(security).build();
        token = bearer(AuthService.ROLE_ADMIN);
        warehouseId = jdbc.queryForObject("SELECT MIN(id) FROM warehouses", Long.class);
        mvc.perform(put("/api/vehicle-maintenance/rules").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"warningKm\":500,\"policies\":[{\"tonnage\":99.99,\"minorIntervalKm\":3000,\"majorIntervalKm\":20000,\"retirementKm\":500000}]}"))
                .andExpect(status().isOk());
        String response = mvc.perform(post("/api/vehicles").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"warehouseId\":" + warehouseId + ",\"plateNumber\":\"MAINT-TEST\",\"capacity\":50,\"status\":\"AVAILABLE\",\"tonnage\":99.99,\"currentOdometerKm\":8000,\"lastMinorMaintenanceKm\":5000,\"lastMajorMaintenanceKm\":6000}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        Number id = JsonPath.read(response, "$.id"); vehicleId = id.longValue();
    }

    private String updateBody(String status, int current) {
        return "{\"warehouseId\":" + warehouseId + ",\"plateNumber\":\"MAINT-TEST\",\"capacity\":50,\"status\":\"" + status + "\",\"tonnage\":99.99,\"currentOdometerKm\":" + current + "}";
    }
    private void update(String status, int current) throws Exception {
        mvc.perform(put("/api/vehicles/" + vehicleId).header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content(updateBody(status, current)))
                .andExpect(status().isOk());
    }

    @Test void sentServiceIsRecordedOnceAndOnlyCompletionResetsBaseline() throws Exception {
        update("MINOR_MAINTENANCE", 8000); update("MINOR_MAINTENANCE", 8000);
        mvc.perform(get("/api/vehicle-maintenance/" + vehicleId + "/history").header("Authorization", token))
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$[0].sentAt").isNotEmpty());
        mvc.perform(get("/api/vehicles/" + vehicleId).header("Authorization", token))
                .andExpect(jsonPath("$.maintenance.minorCount").value(0)).andExpect(jsonPath("$.lastMinorMaintenanceKm").value(5000));
        update("AVAILABLE", 8000);
        mvc.perform(get("/api/vehicles/" + vehicleId).header("Authorization", token))
                .andExpect(jsonPath("$.maintenance.minorCount").value(1)).andExpect(jsonPath("$.lastMinorMaintenanceKm").value(8000))
                .andExpect(jsonPath("$.lastMajorMaintenanceKm").value(6000)).andExpect(jsonPath("$.maintenance.minorRemainingKm").value(3000))
                .andExpect(jsonPath("$.maintenance.lastMinorAt").isNotEmpty());
        mvc.perform(delete("/api/vehicles/" + vehicleId).header("Authorization", token)).andExpect(status().isBadRequest());
    }
    @Test void majorDoesNotResetMinorAndCancellationDoesNotCount() throws Exception {
        update("MAJOR_MAINTENANCE", 8000); update("AVAILABLE", 8000);
        mvc.perform(get("/api/vehicles/" + vehicleId).header("Authorization", token))
                .andExpect(jsonPath("$.maintenance.majorCount").value(1)).andExpect(jsonPath("$.lastMajorMaintenanceKm").value(8000))
                .andExpect(jsonPath("$.lastMinorMaintenanceKm").value(5000));
        update("MINOR_MAINTENANCE", 8000);
        mvc.perform(post("/api/vehicle-maintenance/" + vehicleId + "/cancel").header("Authorization", token)).andExpect(status().isOk());
        mvc.perform(get("/api/vehicles/" + vehicleId).header("Authorization", token))
                .andExpect(jsonPath("$.status").value("AVAILABLE")).andExpect(jsonPath("$.maintenance.minorCount").value(0))
                .andExpect(jsonPath("$.lastMinorMaintenanceKm").value(5000));
    }
    @Test void repairsBlockDispatchButDoNotCountOrResetRoutineService() throws Exception {
        update("MAINTENANCE", 8000);
        update("MAINTENANCE", 8000);
        mvc.perform(get("/api/vehicle-maintenance/" + vehicleId + "/history").header("Authorization", token))
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].type").value("REPAIR"))
                .andExpect(jsonPath("$[0].status").value("ACTIVE")).andExpect(jsonPath("$[0].sentAt").isNotEmpty());
        mvc.perform(get("/api/vehicles/" + vehicleId).header("Authorization", token))
                .andExpect(jsonPath("$.status").value("MAINTENANCE"))
                .andExpect(jsonPath("$.maintenance.decision").value("BLOCKED"))
                .andExpect(jsonPath("$.maintenance.repairCount").value(0));
        assertThrows(IllegalArgumentException.class, () -> maintenance.assertCanDispatch(vehicles.findById(vehicleId).orElseThrow(), 0));
        update("AVAILABLE", 8000);
        update("AVAILABLE", 8000);
        mvc.perform(get("/api/vehicles/" + vehicleId).header("Authorization", token))
                .andExpect(jsonPath("$.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.currentOdometerKm").value(8000))
                .andExpect(jsonPath("$.lastMinorMaintenanceKm").value(5000))
                .andExpect(jsonPath("$.lastMajorMaintenanceKm").value(6000))
                .andExpect(jsonPath("$.maintenance.minorCount").value(0))
                .andExpect(jsonPath("$.maintenance.majorCount").value(0))
                .andExpect(jsonPath("$.maintenance.repairCount").value(1))
                .andExpect(jsonPath("$.maintenance.lastRepairAt").isNotEmpty())
                .andExpect(jsonPath("$.maintenance.minorRemainingKm").value(0))
                .andExpect(jsonPath("$.maintenance.majorRemainingKm").value(18000))
                .andExpect(jsonPath("$.maintenance.lastMinorAt").isEmpty())
                .andExpect(jsonPath("$.maintenance.lastMajorAt").isEmpty());
        mvc.perform(get("/api/vehicle-maintenance/" + vehicleId + "/history").header("Authorization", token))
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].type").value("REPAIR"))
                .andExpect(jsonPath("$[0].status").value("COMPLETED")).andExpect(jsonPath("$[0].completedAt").isNotEmpty())
                .andExpect(jsonPath("$[0].completedOdometerKm").value(8000));
        mvc.perform(delete("/api/vehicles/" + vehicleId).header("Authorization", token)).andExpect(status().isBadRequest());
        assertThrows(IllegalArgumentException.class, () -> maintenance.assertCanDispatch(vehicles.findById(vehicleId).orElseThrow(), 1));
    }
    @Test void cancelledRepairsDoNotCountAndSecondRepairCountsIndependently() throws Exception {
        update("MAINTENANCE", 8000);
        mvc.perform(post("/api/vehicle-maintenance/" + vehicleId + "/cancel").header("Authorization", token)).andExpect(status().isOk());
        mvc.perform(get("/api/vehicles/" + vehicleId).header("Authorization", token))
                .andExpect(jsonPath("$.maintenance.repairCount").value(0))
                .andExpect(jsonPath("$.lastMinorMaintenanceKm").value(5000)).andExpect(jsonPath("$.lastMajorMaintenanceKm").value(6000));
        update("MAINTENANCE", 8000); update("AVAILABLE", 8000);
        update("MAINTENANCE", 8000); update("AVAILABLE", 8000);
        mvc.perform(get("/api/vehicles/" + vehicleId).header("Authorization", token))
                .andExpect(jsonPath("$.maintenance.repairCount").value(2))
                .andExpect(jsonPath("$.maintenance.minorCount").value(0)).andExpect(jsonPath("$.maintenance.majorCount").value(0))
                .andExpect(jsonPath("$.lastMinorMaintenanceKm").value(5000)).andExpect(jsonPath("$.lastMajorMaintenanceKm").value(6000));
        mvc.perform(get("/api/vehicle-maintenance/" + vehicleId + "/history").header("Authorization", token))
                .andExpect(jsonPath("$.length()").value(3)).andExpect(jsonPath("$[2].status").value("CANCELLED"));
    }
    @Test void rulesSynchronizeAndRealOdometerControlsGuard() throws Exception {
        mvc.perform(put("/api/vehicle-maintenance/rules").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"warningKm\":500,\"policies\":[{\"tonnage\":99.99,\"minorIntervalKm\":4000,\"majorIntervalKm\":20000,\"retirementKm\":500000}]}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/vehicles/" + vehicleId).header("Authorization", token))
                .andExpect(jsonPath("$.maintenance.minorRemainingKm").value(1000));
        assertDoesNotThrow(() -> maintenance.assertCanDispatch(vehicles.findById(vehicleId).orElseThrow(), 1000));
        assertThrows(IllegalArgumentException.class, () -> maintenance.assertCanDispatch(vehicles.findById(vehicleId).orElseThrow(), 1001));
    }
    @Test void driversCannotChangeMaintenanceRulesOrReadHistory() throws Exception {
        mvc.perform(get("/api/vehicle-maintenance/rules").header("Authorization", bearer(AuthService.ROLE_DRIVER))).andExpect(status().isForbidden());
        mvc.perform(get("/api/vehicle-maintenance/" + vehicleId + "/history").header("Authorization", bearer(AuthService.ROLE_DRIVER))).andExpect(status().isForbidden());
    }
    @Test void invalidRulesAndBackwardOdometerAreRejected() throws Exception {
        mvc.perform(put("/api/vehicle-maintenance/rules").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"warningKm\":-1,\"policies\":[]}")).andExpect(status().isBadRequest());
        mvc.perform(put("/api/vehicles/" + vehicleId).header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content(updateBody("AVAILABLE", 7999))).andExpect(status().isBadRequest());
        mvc.perform(put("/api/vehicles/" + vehicleId).header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content(updateBody("AVAILABLE", 8050))).andExpect(status().isBadRequest());
        mvc.perform(put("/api/vehicles/" + vehicleId).header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content(updateBody("AVAILABLE", 8000).replace("\"tonnage\":99.99", "\"tonnage\":99.99,\"lastMinorMaintenanceKm\":7000")))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/vehicles/" + vehicleId).header("Authorization", token))
                .andExpect(jsonPath("$.currentOdometerKm").value(8000)).andExpect(jsonPath("$.lastMinorMaintenanceKm").value(5000));
    }
    private String bearer(String role) {
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer(AuthService.TOKEN_ISSUER).subject("maintenance-api-test")
                .issuedAt(now).expiresAt(now.plusSeconds(300)).claim("userId", 1L).claim("name", "測試").claim("role", role).build();
        return "Bearer " + jwt.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }
}
