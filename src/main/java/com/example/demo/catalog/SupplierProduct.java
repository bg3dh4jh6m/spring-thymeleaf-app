package com.example.demo.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.Instant;

/** The supplier's listing/offer linked to a HOREMAG master product. */
@Entity
@Table(name = "supplier_product", uniqueConstraints = {
        @UniqueConstraint(name = "uk_supplier_external_product", columnNames = {"supplier", "external_id"})
})
public class SupplierProduct {

    @Id
    @Column(name = "supplier_code", length = 32, nullable = false)
    private String supplierCode;

    @Column(nullable = false, length = 40)
    private String supplier;

    @Column(name = "external_id", nullable = false, length = 64)
    private String externalId;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "catalog_product_id", nullable = false)
    private CatalogProduct catalogProduct;

    @Column(name = "supplier_name", nullable = false, length = 500)
    private String supplierName;

    @Column(length = 500)
    private String category;

    @Column(name = "package_options", length = 1000)
    private String packageOptions;

    @Column(name = "price_min_eur", precision = 12, scale = 2)
    private BigDecimal priceMinEur;

    @Column(name = "price_max_eur", precision = 12, scale = 2)
    private BigDecimal priceMaxEur;

    @Column(name = "product_url", length = 1500)
    private String productUrl;

    @Column(name = "image_url", length = 1500)
    private String imageUrl;

    @Column(name = "match_method", nullable = false, length = 32)
    private String matchMethod;

    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;

    protected SupplierProduct() {
    }

    public SupplierProduct(String supplierCode) { this.supplierCode = supplierCode; }

    public String getSupplierCode() { return supplierCode; }
    public String getSupplier() { return supplier; }
    public void setSupplier(String supplier) { this.supplier = supplier; }
    public String getExternalId() { return externalId; }
    public void setExternalId(String externalId) { this.externalId = externalId; }
    public CatalogProduct getCatalogProduct() { return catalogProduct; }
    public void setCatalogProduct(CatalogProduct catalogProduct) { this.catalogProduct = catalogProduct; }
    public String getSupplierName() { return supplierName; }
    public void setSupplierName(String supplierName) { this.supplierName = supplierName; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getPackageOptions() { return packageOptions; }
    public void setPackageOptions(String packageOptions) { this.packageOptions = packageOptions; }
    public BigDecimal getPriceMinEur() { return priceMinEur; }
    public void setPriceMinEur(BigDecimal priceMinEur) { this.priceMinEur = priceMinEur; }
    public BigDecimal getPriceMaxEur() { return priceMaxEur; }
    public void setPriceMaxEur(BigDecimal priceMaxEur) { this.priceMaxEur = priceMaxEur; }
    public String getProductUrl() { return productUrl; }
    public void setProductUrl(String productUrl) { this.productUrl = productUrl; }
    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }
    public String getMatchMethod() { return matchMethod; }
    public void setMatchMethod(String matchMethod) { this.matchMethod = matchMethod; }
    public Instant getCapturedAt() { return capturedAt; }
    public void setCapturedAt(Instant capturedAt) { this.capturedAt = capturedAt; }
}
