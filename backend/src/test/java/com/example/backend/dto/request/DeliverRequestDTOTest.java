package com.example.backend.dto.request;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DeliverRequestDTOTest {
    @Test
    void driverDeliveryAcceptsOmittedOrLegacyZeroDamageCount() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var request = delivery();
            assertTrue(factory.getValidator().validate(request).isEmpty());
            request.setDamagedBoxCount(0);
            assertTrue(factory.getValidator().validate(request).isEmpty());
        }
    }

    @Test
    void driverCannotReportDamageEvenWithAnOlderClient() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var request = delivery();
            request.setDamagedBoxCount(1);
            var violations = factory.getValidator().validate(request);
            assertEquals(1, violations.size());
            var violation = violations.iterator().next();
            assertEquals("damagedBoxCount", violation.getPropertyPath().toString());
            assertEquals("貨物損毀由門市回報，司機端不可登記損毀箱數", violation.getMessage());
        }
    }

    @Test
    void negativeDamageCountRemainsInvalid() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var request = delivery();
            request.setDamagedBoxCount(-1);
            assertFalse(factory.getValidator().validate(request).isEmpty());
        }
    }

    private DeliverRequestDTO delivery() {
        var request = new DeliverRequestDTO();
        request.setOrderId(1L);
        request.setBoxCount(10);
        return request;
    }
}
