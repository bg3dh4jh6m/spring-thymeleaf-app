package com.example.demo.catalog;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SupplierOfferRepository extends JpaRepository<SupplierOffer, String> {

    @Query("select offer from SupplierOffer offer " +
            "join fetch offer.supplierProduct product " +
            "join fetch product.catalogProduct " +
            "where product.supplier = :supplier and offer.active = true " +
            "order by product.supplierName, offer.packageGrams")
    List<SupplierOffer> findActiveBySupplier(@Param("supplier") String supplier);

    @Query("select offer from SupplierOffer offer " +
            "join fetch offer.supplierProduct product " +
            "join fetch product.catalogProduct " +
            "where product.supplier = :supplier")
    List<SupplierOffer> findAllBySupplier(@Param("supplier") String supplier);
}
