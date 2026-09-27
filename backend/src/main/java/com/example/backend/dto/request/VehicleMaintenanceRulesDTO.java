package com.example.backend.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.List;

public record VehicleMaintenanceRulesDTO(
        @NotNull @Min(0) Integer warningKm,
        @NotNull List<@Valid Rule> policies) {
    public record Rule(
            @NotNull @DecimalMin("0.01") @DecimalMax("9999.99") @Digits(integer = 4, fraction = 2) BigDecimal tonnage,
            @NotNull @Min(1) Integer minorIntervalKm,
            @NotNull @Min(1) Integer majorIntervalKm,
            @NotNull @Min(1) Integer retirementKm) {}
}
