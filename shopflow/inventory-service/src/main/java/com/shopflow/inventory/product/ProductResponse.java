package com.shopflow.inventory.product;

public record ProductResponse(String sku, String name, int quantity) {

    static ProductResponse from(Product product) {
        return new ProductResponse(product.getSku(), product.getName(), product.getQuantity());
    }
}
