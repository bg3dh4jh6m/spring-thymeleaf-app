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
@Table(name = "supplier_offer_price_history", indexes = {
        @Index(name = "idx_offer_price_history_time", columnList = "offer_code,captured_at")
})
public class SupplierOfferPriceHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "offer_code", nullable = false, length = 64)
    private String offerCode;

    @Column(name = "price_eur", nullable = false, precision = 12, scale = 2)
    private BigDecimal priceEur;

    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;

    protected SupplierOfferPriceHistory() {
    }

    public SupplierOfferPriceHistory(String offerCode, BigDecimal priceEur, Instant capturedAt) {
        this.offerCode = offerCode;
        this.priceEur = priceEur;
        this.capturedAt = capturedAt;
    }
}
