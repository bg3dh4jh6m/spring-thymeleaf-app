package com.example.demo.catalog;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SupplierProductRepository extends JpaRepository<SupplierProduct, String> {
    List<SupplierProduct> findBySupplierOrderBySupplierNameAsc(String supplier);
}
