package com.example.backend.controller;

import com.example.backend.dto.respones.ReportPreTripResponse;
import com.example.backend.service.ReportOutcomesService;
import com.example.backend.service.ReportPerformanceService;
import com.example.backend.service.ReportPreTripService;
import com.example.backend.service.ReportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ReportPreTripControllerTest {
    private final ReportPreTripService service = mock(ReportPreTripService.class);
    private MockMvc mvc;
    @TempDir Path temp;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ReportController(mock(ReportService.class),
                mock(ReportPerformanceService.class), mock(ReportOutcomesService.class), service)).build();
    }

    @Test
    void historyParsesSharedFiltersAndSerializesChecksWithoutStorageNames() throws Exception {
        var date = LocalDate.of(2026, 9, 28);
        var record = new ReportPreTripResponse.Inspection(7L, date, date.atTime(8, 0),
                3L, "陳柏宇", "DRV001", 4L, "KAE-2081", 1L, "高雄左營倉", 10L, 1,
                BigDecimal.ZERO, false, null, "煞車燈待修", true, true,
                List.of(new ReportPreTripResponse.Check("brakeLights", "四燈", "煞車燈", false)),
                List.of("煞車燈"));
        when(service.history(any(), eq(1L), eq(5L), eq(3L), eq(Set.of(4L, 6L))))
                .thenReturn(new ReportPreTripResponse(date, date, List.of(record)));

        mvc.perform(get("/api/reports/pre-trip").param("period", "CUSTOM")
                        .param("from", "2026-09-28").param("to", "2026-09-28")
                        .param("warehouseId", "1").param("storeId", "5").param("driverId", "3")
                        .param("vehicleIds", "4,6"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.inspections[0].inspectionId").value(7))
                .andExpect(jsonPath("$.inspections[0].checks[0].normal").value(false))
                .andExpect(jsonPath("$.inspections[0].hasAlcoholPhoto").value(true))
                .andExpect(jsonPath("$.inspections[0].alcoholPhoto").doesNotExist());
        var range = ArgumentCaptor.forClass(ReportService.Range.class);
        verify(service).history(range.capture(), eq(1L), eq(5L), eq(3L), eq(Set.of(4L, 6L)));
        assertEquals(date, range.getValue().getFrom());
        assertEquals(date, range.getValue().getTo());
    }

    @Test
    void photoResponseHasImageTypeAndNoStoreCaching() throws Exception {
        Path file = temp.resolve("photo.png");
        byte[] bytes = {1, 2, 3};
        Files.write(file, bytes);
        when(service.photo(7L, "alcohol")).thenReturn(file);
        mvc.perform(get("/api/reports/pre-trip/7/photos/alcohol"))
                .andExpect(status().isOk()).andExpect(content().contentType("image/png"))
                .andExpect(content().bytes(bytes)).andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void missingHistoricPhotoReturns404InsteadOfAnEmptyImage() throws Exception {
        when(service.photo(7L, "fault")).thenReturn(temp.resolve("missing.jpg"));
        mvc.perform(get("/api/reports/pre-trip/7/photos/fault")).andExpect(status().isNotFound());
    }
}
