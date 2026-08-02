package com.example.demo.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/** A supplier-independent product owned by HOREMAG. */
@Entity
@Table(name = "catalog_product", uniqueConstraints = {
        @UniqueConstraint(name = "uk_catalog_product_code", columnNames = "internal_code"),
        @UniqueConstraint(name = "uk_catalog_product_normalized_name", columnNames = "normalized_name")
})
public class CatalogProduct {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "internal_code", length = 32, unique = true)
    private String internalCode;

    @Column(nullable = false, length = 500)
    private String name;

    @Column(name = "normalized_name", nullable = false, length = 500)
    private String normalizedName;

    @Column(length = 500)
    private String category;

    @Column(name = "base_unit", nullable = false, length = 24)
    private String baseUnit = "бр";

    @Column(name = "product_type", length = 32)
    private String productType = "INGREDIENT";

    @Column(name = "package_options", length = 1000)
    private String packageOptions;

    @Column(length = 2000)
    private String notes;

    @Column(length = 64)
    private String source;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public CatalogProduct() {
    }

    public Long getId() { return id; }
    public String getInternalCode() { return internalCode; }
    public void setInternalCode(String internalCode) { this.internalCode = internalCode; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getNormalizedName() { return normalizedName; }
    public void setNormalizedName(String normalizedName) { this.normalizedName = normalizedName; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getBaseUnit() { return baseUnit; }
    public void setBaseUnit(String baseUnit) { this.baseUnit = baseUnit; }
    public String getProductType() { return productType; }
    public void setProductType(String productType) { this.productType = productType; }
    public String getPackageOptions() { return packageOptions; }
    public void setPackageOptions(String packageOptions) { this.packageOptions = packageOptions; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
