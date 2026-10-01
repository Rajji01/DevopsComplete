package com.shopflow.order.inventory;

import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;

/**
 * HTTP client for inventory-service. Resilience4j wraps each call as Retry(CircuitBreaker(call)):
 * transient errors (timeouts, 5xx) are retried with backoff, and when most recent calls fail
 * the breaker opens and calls fail fast instead of piling up threads on a dead service.
 * Retrying reserve is safe because inventory-service treats orderRef as an idempotency key.
 */
@Component
public class InventoryClient {

    private record ReserveRequest(String orderRef, String sku, int quantity) {
    }

    private final RestClient restClient;

    // the auto-configured RestClient.Builder carries metrics + trace propagation
    public InventoryClient(RestClient.Builder builder, InventoryProperties properties) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());
        this.restClient = builder
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    @Retry(name = "inventory")
    @CircuitBreaker(name = "inventory")
    public void reserve(String orderRef, String sku, int quantity) {
        restClient.post()
                .uri("/api/v1/reservations")
                .body(new ReserveRequest(orderRef, sku, quantity))
                .retrieve()
                .onStatus(status -> status.isSameCodeAs(HttpStatus.CONFLICT), (req, res) -> {
                    throw new InventoryRejectedException("Insufficient stock for " + sku);
                })
                .onStatus(status -> status.isSameCodeAs(HttpStatus.NOT_FOUND), (req, res) -> {
                    throw new InventoryRejectedException("Unknown product " + sku);
                })
                .toBodilessEntity();
    }

    @Retry(name = "inventory")
    @CircuitBreaker(name = "inventory")
    public void release(String orderRef) {
        restClient.delete()
                .uri("/api/v1/reservations/{orderRef}", orderRef)
                .retrieve()
                .toBodilessEntity();
    }
}
