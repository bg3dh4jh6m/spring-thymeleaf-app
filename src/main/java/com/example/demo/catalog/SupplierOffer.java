package com.example.demo.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/** One purchasable supplier variation with one package size and one exact price. */
@Entity
@Table(name = "supplier_offer")
public class SupplierOffer {

    @Id
    @Column(name = "offer_code", length = 64, nullable = false)
    private String offerCode;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "supplier_code", nullable = false)
    private SupplierProduct supplierProduct;

    @Column(name = "variant_external_id", nullable = false, length = 64)
    private String variantExternalId;

    @Column(name = "package_label", length = 80)
    private String packageLabel;

    @Column(name = "package_grams")
    private Integer packageGrams;

    @Column(name = "units_per_package", nullable = false)
    private Integer unitsPerPackage = 1;

    @Column(name = "price_eur", nullable = false, precision = 12, scale = 2)
    private BigDecimal priceEur;

    @Column(name = "price_per_kg_eur", precision = 12, scale = 2)
    private BigDecimal pricePerKgEur;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;

    protected SupplierOffer() {
    }

    public SupplierOffer(String offerCode) { this.offerCode = offerCode; }

    public String getOfferCode() { return offerCode; }
    public SupplierProduct getSupplierProduct() { return supplierProduct; }
    public void setSupplierProduct(SupplierProduct supplierProduct) { this.supplierProduct = supplierProduct; }
    public String getVariantExternalId() { return variantExternalId; }
    public void setVariantExternalId(String variantExternalId) { this.variantExternalId = variantExternalId; }
    public String getPackageLabel() { return packageLabel; }
    public void setPackageLabel(String packageLabel) { this.packageLabel = packageLabel; }
    public Integer getPackageGrams() { return packageGrams; }
    public void setPackageGrams(Integer packageGrams) { this.packageGrams = packageGrams; }
    public Integer getUnitsPerPackage() { return unitsPerPackage; }
    public void setUnitsPerPackage(Integer unitsPerPackage) { this.unitsPerPackage = unitsPerPackage; }
    public BigDecimal getPriceEur() { return priceEur; }
    public void setPriceEur(BigDecimal priceEur) { this.priceEur = priceEur; }
    public BigDecimal getPricePerKgEur() { return pricePerKgEur; }
    public void setPricePerKgEur(BigDecimal pricePerKgEur) { this.pricePerKgEur = pricePerKgEur; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public Instant getCapturedAt() { return capturedAt; }
    public void setCapturedAt(Instant capturedAt) { this.capturedAt = capturedAt; }
}
