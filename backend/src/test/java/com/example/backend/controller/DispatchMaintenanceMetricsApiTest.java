package com.example.backend.controller;

import com.example.backend.constants.*;
import com.example.backend.dao.*;
import com.example.backend.dispatch.*;
import com.example.backend.entity.*;
import com.example.backend.service.AuthService;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import java.math.BigDecimal;
import java.time.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 真 API 回應與保養公式；OSRM 固定測試值，所有資料 rollback，不改使用者路線。 */
@SpringBootTest(properties = {"app.crypto.password=test-only-password-test-only-password", "app.crypto.salt=0123456789abcdef"})
@Transactional
class DispatchMaintenanceMetricsApiTest {
    @Autowired WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") Filter security;
    @Autowired JwtEncoder jwt;
    @Autowired JdbcTemplate jdbc;
    @Autowired VehiclesDAO vehicles;
    @Autowired RoutesDAO routes;
    @Autowired OrdersDAO orders;
    @Autowired VehicleMaintenancePolicyDAO policies;
    @MockitoBean OsrmClient osrm;

    @Test void 草稿及發布回應均含預估保養但結單後不再扣未來行程() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(security).build();
        Long warehouseId = jdbc.queryForObject("SELECT MIN(id) FROM warehouses WHERE lat IS NOT NULL AND lng IS NOT NULL", Long.class);
        Long storeId = jdbc.queryForObject("SELECT MIN(id) FROM stores WHERE lat IS NOT NULL AND lng IS NOT NULL", Long.class);
        var policy = new VehicleMaintenancePolicy(); policy.tonnage = new BigDecimal("99.97");
        policy.minorIntervalKm = 3000; policy.majorIntervalKm = 20000; policy.retirementKm = 500000;
        policies.saveAndFlush(policy);
        var vehicle = new VehiclesEntity(); vehicle.setWarehouseId(warehouseId); vehicle.setPlateNumber("PROJ-" + UUID.randomUUID().toString().substring(0, 8));
        vehicle.setCapacity(50); vehicle.setTonnage(policy.tonnage); vehicle.setCurrentOdometerKm(100);
        vehicle.setLastMinorMaintenanceKm(0); vehicle.setLastMajorMaintenanceKm(0); vehicle = vehicles.saveAndFlush(vehicle);
        var route = new RoutesEntity(); route.setDate(LocalDate.now(ZoneId.of("Asia/Taipei"))); route.setWarehouseId(warehouseId);
        route.setVehicleId(vehicle.getId()); route = routes.saveAndFlush(route);
        var order = new OrdersEntity(); order.setOrderNumber("PROJ-" + UUID.randomUUID().toString().substring(0, 8));
        order.setStoreId(storeId); order.setWarehouseId(warehouseId); order.setBoxCount(10); order.setDeliveryDate(route.getDate());
        order.setRouteId(route.getId()); order.setSequence(1); order.setStatus(OrderStatus.CONFIRMED); orders.saveAndFlush(order);
        var road = new OsrmRouteResponse.Route(); road.setDistance(15000); road.setDuration(900); when(osrm.route(any(), any())).thenReturn(road);
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer(AuthService.TOKEN_ISSUER).subject("maintenance-preview-test")
                .issuedAt(now).expiresAt(now.plusSeconds(300)).claim("userId", 1L).claim("role", AuthService.ROLE_ADMIN).build();
        String token = "Bearer " + jwt.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        for (RouteStatus status : new RouteStatus[]{RouteStatus.DRAFT, RouteStatus.PUBLISHED}) {
            route.setStatus(status); routes.saveAndFlush(route);
            mvc.perform(get("/api/dispatch/routes/" + route.getId() + "/metrics").header("Authorization", token))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.plannedKm").value(30.0))
                    .andExpect(jsonPath("$.maintenance.currentOdometerKm").value(100))
                    .andExpect(jsonPath("$.maintenance.minorRemainingKm").value(2900))
                    .andExpect(jsonPath("$.maintenance.plannedKm").value(30.0))
                    .andExpect(jsonPath("$.maintenance.projectedMinorKm").value(2870.0))
                    .andExpect(jsonPath("$.maintenance.projectedMajorKm").value(19870.0))
                    .andExpect(jsonPath("$.maintenance.projectedRetirementKm").value(499870.0));
        }
        assertEquals(100, vehicles.findById(vehicle.getId()).orElseThrow().getCurrentOdometerKm());
        order.setStatus(OrderStatus.COMPLETED); orders.saveAndFlush(order);
        mvc.perform(get("/api/dispatch/routes/" + route.getId() + "/metrics").header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.plannedKm").value(30.0))
                .andExpect(jsonPath("$.maintenance.plannedKm").isEmpty())
                .andExpect(jsonPath("$.maintenance.currentOdometerKm").value(100));
    }
}
