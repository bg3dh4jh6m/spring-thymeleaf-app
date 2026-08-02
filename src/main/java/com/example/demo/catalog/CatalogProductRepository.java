package com.example.demo.catalog;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.List;

public interface CatalogProductRepository extends JpaRepository<CatalogProduct, Long> {
    Optional<CatalogProduct> findFirstByNormalizedName(String normalizedName);
    List<CatalogProduct> findAllByOrderByCategoryAscNameAsc();
}
