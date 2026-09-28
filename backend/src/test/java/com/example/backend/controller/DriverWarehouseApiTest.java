package com.example.backend.controller;

import com.example.backend.service.AuthService;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.*;
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

/** 真 API + MySQL；所有新增／修改在每個案例結束 rollback，不留下示範帳號。 */
@SpringBootTest(properties = {"app.crypto.password=test-only-password-test-only-password", "app.crypto.salt=0123456789abcdef"})
@Transactional
class DriverWarehouseApiTest {
    @Autowired WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") Filter security;
    @Autowired JwtEncoder jwt;
    @Autowired JdbcTemplate jdbc;
    MockMvc mvc;
    Long first, second, inactive;
    String token;
    @BeforeEach void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(security).build();
        var ids = jdbc.queryForList("SELECT id FROM warehouses WHERE is_active=1 ORDER BY id", Long.class);
        first = ids.get(0); second = ids.get(1);
        inactive = jdbc.queryForObject("SELECT MIN(id) FROM warehouses WHERE is_active=0", Long.class);
        token = bearer(AuthService.ROLE_ADMIN, 1L);
    }
    String body(Long warehouseId, boolean create) {
        return "{\"account\":\"WAREHOUSE-API-TEST\",\"name\":\"測試司機\",\"phone\":\"0912345678\","
                + "\"workStart\":\"08:00\",\"workEnd\":\"17:00\",\"restDuration\":60,\"maxOvertimeMinutes\":30,\"isActive\":true"
                + (warehouseId == null ? "" : ",\"warehouseId\":" + warehouseId)
                + (create ? ",\"password\":\"A123456789\"" : "") + "}";
    }
    Long create() throws Exception {
        String response = mvc.perform(post("/api/drivers").header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content(body(first, true)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.warehouseId").value(first))
                .andExpect(jsonPath("$.warehouseName").isNotEmpty()).andExpect(jsonPath("$.password").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, "$.id")).longValue();
    }
    @Test void createsAndReturnsWarehouseInAdminListAndDriverProfile() throws Exception {
        Long id = create();
        mvc.perform(get("/api/drivers/" + id).header("Authorization", token)).andExpect(jsonPath("$.warehouseId").value(first));
        mvc.perform(get("/api/driver/profile").header("Authorization", bearer(AuthService.ROLE_DRIVER, id)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.warehouseId").value(first)).andExpect(jsonPath("$.warehouseCode").isNotEmpty());
        assertEquals(first, jdbc.queryForObject("SELECT warehouse_id FROM drivers WHERE id=?", Long.class, id));
    }
    @Test void transfersDriverWithoutChangingPasswordOrHistoricalRoutes() throws Exception {
        Long id = create();
        String password = jdbc.queryForObject("SELECT password FROM drivers WHERE id=?", String.class, id);
        var routes = jdbc.queryForList("SELECT id,warehouse_id,driver_id,status FROM routes ORDER BY id");
        mvc.perform(put("/api/drivers/" + id).header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content(body(second, false)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.warehouseId").value(second)).andExpect(jsonPath("$.restDuration").value(60));
        assertEquals(password, jdbc.queryForObject("SELECT password FROM drivers WHERE id=?", String.class, id));
        assertEquals(routes, jdbc.queryForList("SELECT id,warehouse_id,driver_id,status FROM routes ORDER BY id"));
        mvc.perform(patch("/api/drivers/" + id + "/status").header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content("{\"isActive\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.isActive").value(false)).andExpect(jsonPath("$.warehouseId").value(second));
    }
    @Test void rejectsMissingUnknownAndInactiveWarehouseWithoutCreatingDriver() throws Exception {
        for (Long id : new Long[]{null, Long.MAX_VALUE, inactive}) {
            mvc.perform(post("/api/drivers").header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content(body(id, true)))
                    .andExpect(status().isBadRequest());
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM drivers WHERE account='WAREHOUSE-API-TEST'", Integer.class));
    }
    @Test void rejectsInvalidTransferAndDriverRoleCannotEditAffiliation() throws Exception {
        Long id = create();
        mvc.perform(put("/api/drivers/" + id).header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content(body(inactive, false))).andExpect(status().isBadRequest());
        mvc.perform(put("/api/drivers/" + id).header("Authorization", bearer(AuthService.ROLE_DRIVER, id)).contentType(MediaType.APPLICATION_JSON).content(body(second, false))).andExpect(status().isForbidden());
        assertEquals(first, jdbc.queryForObject("SELECT warehouse_id FROM drivers WHERE id=?", Long.class, id));
    }
    String bearer(String role, Long id) {
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer(AuthService.TOKEN_ISSUER).subject("driver-warehouse-api-test")
                .issuedAt(now).expiresAt(now.plusSeconds(300)).claim("userId", id).claim("role", role).build();
        return "Bearer " + jwt.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }
}
