package com.shopflow.inventory.product;

import java.util.List;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.shopflow.inventory.common.ProductNotFoundException;

@RestController
@RequestMapping("/api/v1/products")
@Transactional(readOnly = true)
public class ProductController {

    private final ProductRepository productRepository;

    public ProductController(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @GetMapping
    public List<ProductResponse> list() {
        return productRepository.findAll().stream().map(ProductResponse::from).toList();
    }

    /**
     * Cache-aside: first call hits the DB and stores the DTO (not the entity!) under key = sku;
     * reserve/release evict that key, so a stale quantity is never served longer than one write.
     * The cached value is a record: serialisable and detached from the JPA session.
     */
    @GetMapping("/{sku}")
    @Cacheable(cacheNames = "products", key = "#sku")
    public ProductResponse get(@PathVariable String sku) {
        return productRepository.findBySku(sku)
                .map(ProductResponse::from)
                .orElseThrow(() -> new ProductNotFoundException(sku));
    }
}
