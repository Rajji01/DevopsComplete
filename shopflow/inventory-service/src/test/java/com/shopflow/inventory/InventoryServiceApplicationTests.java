package com.shopflow.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.shopflow.inventory.common.InsufficientStockException;
import com.shopflow.inventory.product.ProductRepository;
import com.shopflow.inventory.reservation.ReservationService;

@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureObservability // metrics exporters (Prometheus) are off in tests by default
@ActiveProfiles("test")
class InventoryServiceApplicationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ReservationService reservationService;

    private int stockOf(String sku) {
        return productRepository.findBySku(sku).orElseThrow().getQuantity();
    }

    private String reserveBody(String orderRef, String sku, int quantity) {
        return """
                {"orderRef":"%s","sku":"%s","quantity":%d}""".formatted(orderRef, sku, quantity);
    }

    @Test
    void listsSeededProducts() throws Exception {
        mockMvc.perform(get("/api/v1/products/AIRPODS-PRO"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("AirPods Pro (2nd gen)"));
    }

    @Test
    void reserveIsIdempotentPerOrderRef() throws Exception {
        String orderRef = UUID.randomUUID().toString();
        int before = stockOf("AIRPODS-PRO");

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/api/v1/reservations")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(reserveBody(orderRef, "AIRPODS-PRO", 2)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("RESERVED"));
        }

        assertThat(stockOf("AIRPODS-PRO")).isEqualTo(before - 2);
    }

    @Test
    void releaseRestoresStockOnlyOnce() throws Exception {
        String orderRef = UUID.randomUUID().toString();
        int before = stockOf("IPHONE-15");
        reservationService.reserve(orderRef, "IPHONE-15", 3);

        mockMvc.perform(delete("/api/v1/reservations/" + orderRef)).andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/v1/reservations/" + orderRef)).andExpect(status().isNoContent());

        assertThat(stockOf("IPHONE-15")).isEqualTo(before);
    }

    @Test
    void insufficientStockReturns409() throws Exception {
        mockMvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reserveBody(UUID.randomUUID().toString(), "PIXEL-9", 100)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Insufficient stock"));
    }

    @Test
    void unknownSkuReturns404() throws Exception {
        mockMvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reserveBody(UUID.randomUUID().toString(), "NOPE", 1)))
                .andExpect(status().isNotFound());
    }

    @Test
    void invalidRequestReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reserveBody("", "PIXEL-9", 0)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void concurrentReservationsNeverOversell() throws Exception {
        // PS5-SLIM starts with 3 units: 20 parallel buyers, exactly 3 must win
        int threads = 20;
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            var futures = new java.util.ArrayList<Future<?>>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    try {
                        reservationService.reserve(UUID.randomUUID().toString(), "PS5-SLIM", 1);
                        succeeded.incrementAndGet();
                    } catch (InsufficientStockException e) {
                        rejected.incrementAndGet();
                    }
                }));
            }
            for (Future<?> f : futures) {
                f.get();
            }
        }

        assertThat(succeeded.get()).isEqualTo(3);
        assertThat(rejected.get()).isEqualTo(17);
        assertThat(stockOf("PS5-SLIM")).isZero();
    }

    @Test
    void exposesHealthProbesAndPrometheusMetrics() throws Exception {
        mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/prometheus")).andExpect(status().isOk());
    }
}
