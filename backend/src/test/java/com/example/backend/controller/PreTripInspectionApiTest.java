package com.example.backend.controller;

import com.example.backend.service.*;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import java.time.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

/** 真實驗證權限、multipart、持久化與點交防護；資料交易 rollback，照片也隨 rollback 清理。 */
@SpringBootTest(properties={"app.crypto.password=test-only-password-test-only-password", "app.crypto.salt=0123456789abcdef",
        "app.storage.pre-trip-dir=build/test-pre-trip-photos"})
@Transactional
class PreTripInspectionApiTest {
    @Autowired WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") Filter security;
    @Autowired JwtEncoder encoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired DispatchWorkflowService workflow;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    com.example.backend.dao.RoutesDAO routesDAO;
    MockMvc mvc; long driver,vehicle,route,order; LocalDate today;
    @BeforeEach void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(security).build(); today = LocalDate.now(ZoneId.of("Asia/Taipei"));
        long warehouse = jdbc.queryForObject("SELECT MIN(id) FROM warehouses",Long.class), store = jdbc.queryForObject("SELECT MIN(id) FROM stores",Long.class);
        jdbc.update("INSERT INTO drivers(account,name,work_start,work_end,rest_duration,is_active) VALUES('PRETRIP-TEST','安全檢查測試','08:00','17:00',60,1)");
        driver = jdbc.queryForObject("SELECT id FROM drivers WHERE account='PRETRIP-TEST'",Long.class);
        jdbc.update("INSERT INTO vehicles(warehouse_id,plate_number,capacity,status) VALUES(?,'PRETRIP-TEST',50,'AVAILABLE')",warehouse);
        vehicle = jdbc.queryForObject("SELECT id FROM vehicles WHERE plate_number='PRETRIP-TEST'",Long.class);
        jdbc.update("INSERT INTO routes(date,warehouse_id,vehicle_id,driver_id,status,version) VALUES(?,?,?,?,'PUBLISHED',1)",today,warehouse,vehicle,driver);
        route = jdbc.queryForObject("SELECT id FROM routes WHERE vehicle_id=?",Long.class,vehicle);
        jdbc.update("INSERT INTO orders(order_number,store_id,warehouse_id,box_count,delivery_date,status,route_id,sequence,assigned_driver_id,assigned_vehicle_id,created_at,updated_at) VALUES('PRETRIP-ORDER',?,?,2,?,'CONFIRMED',?,1,?,?,NOW(),NOW())",store,warehouse,today,route,driver,vehicle);
        order = jdbc.queryForObject("SELECT id FROM orders WHERE order_number='PRETRIP-ORDER'",Long.class);
    }
    String token(long id,String role) {
        Instant now=Instant.now(); var claims=JwtClaimsSet.builder().issuer("logistics-dispatch").subject("pre-trip-api-test").issuedAt(now)
                .expiresAt(now.plusSeconds(600)).claim("userId",id).claim("role",role).build();
        return "Bearer "+encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),claims)).getTokenValue();
    }
    MockMultipartFile request(String value) {
        return new MockMultipartFile("request","request.json","application/json",("{\"routeId\":"+route+",\"alcoholMgL\":"+value
                +",\"alcoholTested\":true,\"headlights\":true,\"taillights\":true,\"turnSignals\":true,\"brakeLights\":true,\"frontLeftTire\":true,\"frontRightTire\":true,\"rearLeftTire\":true,\"rearRightTire\":true,\"dashcam\":true}").getBytes());
    }
    MockMultipartFile photo(String name) throws Exception {
        var stream = new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(1,1,BufferedImage.TYPE_INT_RGB),"png",stream);
        return new MockMultipartFile(name,"check.png","image/png",stream.toByteArray());
    }
    ResultActions submit(String value) throws Exception {
        return mvc.perform(multipart("/api/driver/pre-trip").file(request(value)).file(photo("alcoholPhoto")).file(photo("vehiclePhoto")).file(photo("dashcamPhoto"))
                .header("Authorization",token(driver,AuthService.ROLE_DRIVER))).andDo(response -> {
                    if (response.getResponse().getStatus()!=200) System.out.println("Pre-trip error response: "+response.getResponse().getContentAsString());
                });
    }
    ResultActions load() throws Exception {
        return mvc.perform(post("/api/driver/loading").header("Authorization",token(driver,AuthService.ROLE_DRIVER))
                .contentType(MediaType.APPLICATION_JSON).content("{\"orderId\":"+order+",\"loadedBoxCount\":2}"));
    }
    @Test void 未檢查直接呼叫點交與里程API也會被擋() throws Exception {
        load().andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("安全檢查")));
        mvc.perform(post("/api/driver/mileage/start").header("Authorization",token(driver,AuthService.ROLE_DRIVER))
                .contentType(MediaType.APPLICATION_JSON).content("{\"odometer\":0}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("安全檢查")));
    }
    @Test void 三張照片通過後可點交且照片只給本人() throws Exception {
        String body = submit("0.00").andExpect(status().isOk()).andExpect(jsonPath("$.passed").value(true)).andReturn().getResponse().getContentAsString();
        Number id = JsonPath.read(body,"$.id");
        mvc.perform(get("/api/driver/pre-trip").param("routeId",Long.toString(route)).header("Authorization",token(driver,AuthService.ROLE_DRIVER)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.completed").value(true));
        mvc.perform(get("/api/driver/pre-trip/"+id+"/photos/alcohol").header("Authorization",token(driver,AuthService.ROLE_DRIVER)))
                .andExpect(status().isOk()).andExpect(content().contentType(MediaType.IMAGE_PNG));
        mvc.perform(get("/api/driver/pre-trip/"+id+"/photos/alcohol").header("Authorization",token(driver+1000,AuthService.ROLE_DRIVER))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/driver/pre-trip/"+id+"/photos/alcohol")).andExpect(status().isUnauthorized());
        load().andExpect(status().isOk()).andExpect(jsonPath("$.orderStatus").value("LOADED"));
    }
    @Test void 酒測非零會留紀錄但仍然不能點交() throws Exception {
        submit("0.01").andExpect(status().isOk()).andExpect(jsonPath("$.passed").value(false));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM pre_trip_inspections WHERE route_id=? AND passed=0",Integer.class,route));
        load().andExpect(status().isBadRequest());
    }
    @Test void 缺照片與非司機角色不可提交() throws Exception {
        mvc.perform(multipart("/api/driver/pre-trip").file(request("0.00")).file(photo("alcoholPhoto"))
                .header("Authorization",token(driver,AuthService.ROLE_DRIVER))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/driver/pre-trip").param("routeId",Long.toString(route)).header("Authorization",token(1,AuthService.ROLE_ADMIN))).andExpect(status().isForbidden());
    }
    @Test void 撤回檢查作廢重發布仍要重做() throws Exception {
        submit("0.00").andExpect(status().isOk());
        // 只測新增防護與撤回流程，不對使用者其他當日路線做跨倉庫撤回。
        org.mockito.Mockito.doReturn(java.util.List.of(routesDAO.findById(route).orElseThrow())).when(routesDAO).findByDate(today);
        var preTrip=context.getBean(PreTripInspectionService.class); preTrip.prepareWithdraw(today);
        jdbc.update("UPDATE routes SET status='DRAFT' WHERE id=?",route);
        jdbc.update("UPDATE routes SET status='PUBLISHED' WHERE id=?",route);
        mvc.perform(get("/api/driver/pre-trip").param("routeId",Long.toString(route)).header("Authorization",token(driver,AuthService.ROLE_DRIVER)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.passed").value(false));
        load().andExpect(status().isBadRequest());
    }
}
