package com.shopflow.inventory.product;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductRepository extends JpaRepository<Product, Long> {

    Optional<Product> findBySku(String sku);

    boolean existsBySku(String sku);

    /**
     * Check-and-decrement in a single UPDATE so two concurrent orders can never
     * oversell: the DB row lock serialises them and the WHERE clause re-checks stock.
     * Returns the number of rows updated (0 = unknown SKU or not enough stock).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Product p set p.quantity = p.quantity - :qty where p.sku = :sku and p.quantity >= :qty")
    int decrementStock(@Param("sku") String sku, @Param("qty") int qty);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Product p set p.quantity = p.quantity + :qty where p.sku = :sku")
    int incrementStock(@Param("sku") String sku, @Param("qty") int qty);
}
