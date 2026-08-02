package com.example.demo.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "catalog_price_history", indexes = {
        @Index(name = "idx_price_history_product_time", columnList = "supplier_code,captured_at")
})
public class CatalogPriceHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "supplier_code", nullable = false, length = 32)
    private String supplierCode;

    @Column(name = "price_min_eur", precision = 12, scale = 2)
    private BigDecimal priceMinEur;

    @Column(name = "price_max_eur", precision = 12, scale = 2)
    private BigDecimal priceMaxEur;

    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;

    protected CatalogPriceHistory() {
    }

    public CatalogPriceHistory(String supplierCode, BigDecimal priceMinEur, BigDecimal priceMaxEur, Instant capturedAt) {
        this.supplierCode = supplierCode;
        this.priceMinEur = priceMinEur;
        this.priceMaxEur = priceMaxEur;
        this.capturedAt = capturedAt;
    }
}
