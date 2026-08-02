package com.example.demo.catalog;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CatalogPriceHistoryRepository extends JpaRepository<CatalogPriceHistory, Long> {
}
