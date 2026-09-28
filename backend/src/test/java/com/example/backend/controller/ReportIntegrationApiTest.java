package com.example.backend.controller;

import com.example.backend.service.AuthService;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Verifies V2 reports against the actual JIN schema. Each fixture is rolled back. */
@SpringBootTest(properties = {"app.crypto.password=test-only-password-test-only-password", "app.crypto.salt=0123456789abcdef"})
@Transactional
class ReportIntegrationApiTest {
    private static final String DAY = "2001-02-03";
    private static final String MARKER = "MAJOR-REPORT-API";
    @Autowired WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") Filter security;
    @Autowired JwtEncoder jwt;
    @Autowired JdbcTemplate jdbc;
    private MockMvc mvc;
    private String admin;
    private long warehouse, driver, vehicle, route, order;

    @BeforeEach void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(security).build();
        admin = bearer(AuthService.ROLE_ADMIN, 1L);
        jdbc.update("INSERT INTO warehouses (warehouse_code,name,lat,lng,is_active) VALUES (?, ?,22.68,120.30,1)", MARKER, "Report test warehouse");
        warehouse = id("warehouses", "warehouse_code");
        jdbc.update("INSERT INTO drivers (warehouse_id,account,name,work_start,work_end,rest_duration,is_active) VALUES (?, ?,?,'08:00','17:00',60,1)", warehouse, MARKER, MARKER);
        driver = id("drivers", "account");
        jdbc.update("INSERT INTO vehicles (warehouse_id,plate_number,capacity,status) VALUES (?, ?,50,'AVAILABLE')", warehouse, MARKER);
        vehicle = id("vehicles", "plate_number");
        jdbc.update("INSERT INTO stores (store_code,name,lat,lng,receiving_start,receiving_end,status) VALUES (?, ?,22.69,120.31,'09:00','18:00','ACTIVE')", MARKER, MARKER);
        long store = id("stores", "store_code");
        jdbc.update("INSERT INTO routes (date,warehouse_id,driver_id,vehicle_id,status,version) VALUES (?, ?, ?, ?,'PUBLISHED',2)", DAY, warehouse, driver, vehicle);
        route = jdbc.queryForObject("SELECT id FROM routes WHERE warehouse_id=? AND date=?", Long.class, warehouse, DAY);
        jdbc.update("INSERT INTO orders (order_number,store_id,warehouse_id,box_count,delivery_date,status,route_id,assigned_driver_id,assigned_vehicle_id,sequence,loaded_at,created_at,updated_at) VALUES (?, ?, ?,15,?,'COMPLETED',?, ?, ?,1,'2001-02-03 08:30:00','2001-02-03 07:00:00','2001-02-03 11:00:00')", MARKER, store, warehouse, DAY, route, driver, vehicle);
        order = id("orders", "order_number");
        jdbc.update("INSERT INTO order_items (order_id,item_name,expected_quantity,loaded_quantity,unit,sequence) VALUES (?, 'Test product',15,15,'箱',1)", order);
        jdbc.update("INSERT INTO delivery_records (order_id,arrived_at,delivered_at,handled_at,expected_box_count,delivered_box_count,shortage_box_count,damaged_box_count,replacement_required_box_count,no_signature) VALUES (?, '2001-02-03 10:00:00','2001-02-03 10:10:00','2001-02-03 10:10:00',15,15,0,0,0,0)", order);
        inspection(2, true, false);
    }

    @Test void outcomesIncludesJinInspectionAndItemDetailsWithoutTonnage() throws Exception {
        mvc.perform(get("/api/reports/outcomes").param("date", DAY).param("warehouseId", "" + warehouse).param("includeDetails", "true").header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.delivery.fullDeliveryRate").value(100.0))
                .andExpect(jsonPath("$.safety.passedRoutes").value(1)).andExpect(jsonPath("$.loading.matchRate").value(100.0))
                .andExpect(jsonPath("$.warehouses.length()").value(1)).andExpect(jsonPath("$.orders[0].expectedBoxCount").value(15))
                .andExpect(jsonPath("$.orders[0].deliveredBoxCount").value(15)).andExpect(jsonPath("$.orders[0].items[0].loadedQuantity").value(15));
        mvc.perform(get("/api/reports/outcomes").param("date", DAY).param("warehouseId", "" + warehouse).header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.orders.length()").value(0));
    }

    @Test void obsoleteVersionAndInvalidatedInspectionsCannotOverrideCurrentPass() throws Exception {
        inspection(1, false, false);
        inspection(2, false, true);
        mvc.perform(get("/api/reports/outcomes").param("date", DAY).param("warehouseId", "" + warehouse).header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.safety.inspectedRoutes").value(1))
                .andExpect(jsonPath("$.safety.passedRoutes").value(1)).andExpect(jsonPath("$.safety.failedRoutes").value(0));
    }

    @Test void noRecipientReportsExpectedFifteenActualZeroAndNextDayDoesNotReuseResults() throws Exception {
        jdbc.update("UPDATE orders SET status='NO_SIGNATURE' WHERE id=?", order);
        jdbc.update("UPDATE delivery_records SET delivered_at=NULL,delivered_box_count=0,no_signature=1 WHERE order_id=?", order);
        mvc.perform(get("/api/reports/outcomes").param("date", DAY).param("warehouseId", "" + warehouse).param("includeDetails", "true").header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.problems.noSignatureOrders").value(1))
                .andExpect(jsonPath("$.orders[0].expectedBoxCount").value(15)).andExpect(jsonPath("$.orders[0].deliveredBoxCount").value(0));
        mvc.perform(get("/api/reports/outcomes").param("date", "2001-02-04").param("warehouseId", "" + warehouse).header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.delivery.dueOrders").value(0))
                .andExpect(jsonPath("$.delivery.fullDeliveryRate").isEmpty());
    }

    @Test void performanceUsesCompletedPunchesAndActualJinTripMileage() throws Exception {
        jdbc.update("INSERT INTO schedule_months (generated_at,published_at,schedule_month,status,version) VALUES ('2001-02-01 07:00:00','2001-02-01 08:00:00','2001-02-01','PUBLISHED',0)");
        long month = jdbc.queryForObject("SELECT id FROM schedule_months WHERE schedule_month='2001-02-01'", Long.class);
        jdbc.update("INSERT INTO driver_shifts (schedule_month_id,driver_id,work_date,shift_type,work_start,work_end,last_modified_at,version) VALUES (?, ?,?,'WORK','08:00','17:00','2001-02-01 08:00:00',0)", month, driver, DAY);
        long shift = jdbc.queryForObject("SELECT id FROM driver_shifts WHERE driver_id=? AND work_date=?", Long.class, driver, DAY);
        jdbc.update("INSERT INTO attendance_records (driver_shift_id,driver_id,work_date,clock_in_at,clock_out_at,break_used,status,version) VALUES (?, ?,?,'2001-02-03 08:00:00','2001-02-03 18:00:00',0,'CLOCKED_OUT',0)", shift, driver, DAY);
        jdbc.update("INSERT INTO mileage_logs (driver_id,route_id,vehicle_id,date,start_time,end_time,start_odometer,end_odometer,gps_distance_km,gps_distance_status,mileage_settled_at) VALUES (?, ?, ?,?,'2001-02-03 08:30:00','2001-02-03 16:00:00',1000,1060,52.5,'COMPLETE','2001-02-03 16:05:00')", driver, route, vehicle, DAY);
        mvc.perform(get("/api/reports/performance").param("date", DAY).param("warehouseId", "" + warehouse).header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.workforce.attendanceRate").value(100.0))
                .andExpect(jsonPath("$.workforce.onTimeRate").value(100.0)).andExpect(jsonPath("$.workforce.overtimeRate").value(100.0))
                .andExpect(jsonPath("$.workforce.overtimeMinutes").value(60)).andExpect(jsonPath("$.fleet.startedTrips").value(1))
                .andExpect(jsonPath("$.fleet.actualKm").value(52.5)).andExpect(jsonPath("$.trips[0].distanceSource").value("GPS_SETTLED"));
    }

    @Test void reportsRemainSupervisorOnlyAndRejectInvalidDateRange() throws Exception {
        for (String endpoint : new String[]{"outcomes", "performance"}) {
            mvc.perform(get("/api/reports/" + endpoint).param("date", DAY).header("Authorization", bearer(AuthService.ROLE_DRIVER, driver)))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/api/reports/" + endpoint).param("from", "2001-02-04").param("to", DAY).header("Authorization", admin))
                    .andExpect(status().isBadRequest());
        }
    }

    private long id(String table, String key) {
        return jdbc.queryForObject("SELECT id FROM " + table + " WHERE " + key + "=?", Long.class, MARKER);
    }
    private void inspection(int version, boolean passed, boolean invalidated) {
        jdbc.update("INSERT INTO pre_trip_inspections (route_id,driver_id,vehicle_id,route_version,work_date,alcohol_mg_l,dashcam,engine_oil,brake_fluid,power_steering_fluid,transmission_oil,fuel,coolant,battery_water,washer_fluid,tire_pressure,tire_tread,headlights,turn_signals,brake_lights,dashboard_lights,alcohol_photo,passed,submitted_at,invalidated_at) VALUES (?, ?, ?, ?,?,0.00,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,'test-only.jpg',?,'2001-02-03 08:20:00',?)", route, driver, vehicle, version, DAY, passed, invalidated ? "2001-02-03 08:25:00" : null);
    }
    private String bearer(String role, long id) {
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer(AuthService.TOKEN_ISSUER).subject(MARKER).issuedAt(now)
                .expiresAt(now.plusSeconds(300)).claim("userId", id).claim("role", role).build();
        return "Bearer " + jwt.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }
}
