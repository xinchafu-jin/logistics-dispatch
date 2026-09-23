package com.example.backend.dto.respones;

import com.example.backend.constants.FuelType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 貨車柴油價格查詢結果。 */
public class FuelPriceResponse {

    private Long id;
    private FuelType fuelType;
    private String fuelName;
    private BigDecimal pricePerLiter;
    private LocalDateTime effectiveFrom;
    private String source;
    private LocalDateTime fetchedAt;

    public FuelPriceResponse() {
    }

    public FuelPriceResponse(
            Long id,
            FuelType fuelType,
            String fuelName,
            BigDecimal pricePerLiter,
            LocalDateTime effectiveFrom,
            String source,
            LocalDateTime fetchedAt
    ) {
        this.id = id;
        this.fuelType = fuelType;
        this.fuelName = fuelName;
        this.pricePerLiter = pricePerLiter;
        this.effectiveFrom = effectiveFrom;
        this.source = source;
        this.fetchedAt = fetchedAt;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public FuelType getFuelType() {
        return fuelType;
    }

    public void setFuelType(FuelType fuelType) {
        this.fuelType = fuelType;
    }

    public String getFuelName() {
        return fuelName;
    }

    public void setFuelName(String fuelName) {
        this.fuelName = fuelName;
    }

    public BigDecimal getPricePerLiter() {
        return pricePerLiter;
    }

    public void setPricePerLiter(BigDecimal pricePerLiter) {
        this.pricePerLiter = pricePerLiter;
    }

    public LocalDateTime getEffectiveFrom() {
        return effectiveFrom;
    }

    public void setEffectiveFrom(LocalDateTime effectiveFrom) {
        this.effectiveFrom = effectiveFrom;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public LocalDateTime getFetchedAt() {
        return fetchedAt;
    }

    public void setFetchedAt(LocalDateTime fetchedAt) {
        this.fetchedAt = fetchedAt;
    }
}
