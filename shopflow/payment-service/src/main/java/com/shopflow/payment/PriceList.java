package com.shopflow.payment;

import java.math.BigDecimal;
import java.util.Map;

import org.springframework.stereotype.Component;

/** Prices live here for the demo; a real system asks a catalog service or carries the price in the event. */
@Component
public class PriceList {

    private static final Map<String, BigDecimal> PRICES = Map.of(
            "IPHONE-15", new BigDecimal("79900.00"),
            "PIXEL-9", new BigDecimal("74999.00"),
            "AIRPODS-PRO", new BigDecimal("24900.00"),
            "PS5-SLIM", new BigDecimal("54990.00"));

    public BigDecimal priceOf(String sku) {
        return PRICES.getOrDefault(sku, new BigDecimal("999.00"));
    }
}
