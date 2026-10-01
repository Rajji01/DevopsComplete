package com.shopflow.order.inventory;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Typed config for the inventory-service client (prefix "inventory" in application.yml). */
@ConfigurationProperties(prefix = "inventory")
public record InventoryProperties(String baseUrl, Duration connectTimeout, Duration readTimeout) {
}
