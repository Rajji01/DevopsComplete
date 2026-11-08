package com.shopflow.payment;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

/**
 * Prices live here for the demo; a real system asks a catalog service or carries the price in the
 * event. An unknown sku returns empty — it used to silently fall back to 999.00, which meant
 * "add a product to inventory, forget it here, and every order for it is charged a made-up
 * price" (Walk 42). Never invent an amount of money: the caller declines the charge instead.
 */
@Component
public class PriceList {

    private static final Map<String, BigDecimal> PRICES = Map.of(
            "IPHONE-15", new BigDecimal("79900.00"),
            "PIXEL-9", new BigDecimal("74999.00"),
            "AIRPODS-PRO", new BigDecimal("24900.00"),
            "PS5-SLIM", new BigDecimal("54990.00"));

    public Optional<BigDecimal> priceOf(String sku) {
        return Optional.ofNullable(PRICES.get(sku));
    }
}
