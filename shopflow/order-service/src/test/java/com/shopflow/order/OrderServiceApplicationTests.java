package com.shopflow.order;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.jayway.jsonpath.JsonPath;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

/** Full Spring context + real HTTP calls to a WireMock stand-in for inventory-service. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrderServiceApplicationTests {

    static final WireMockServer inventory = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        inventory.start();
    }

    @DynamicPropertySource
    static void inventoryUrl(DynamicPropertyRegistry registry) {
        registry.add("inventory.base-url", inventory::baseUrl);
    }

    @AfterAll
    static void stopInventory() {
        inventory.stop();
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @BeforeEach
    void reset() {
        inventory.resetAll();
        circuitBreakerRegistry.circuitBreaker("inventory").reset();
    }

    private static MockHttpServletRequestBuilder createOrder(String sku, int quantity) {
        return MockMvcRequestBuilders.post("/api/v1/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"sku":"%s","quantity":%d}""".formatted(sku, quantity));
    }

    private static void stubReserve(int status) {
        inventory.stubFor(post(urlEqualTo("/api/v1/reservations")).willReturn(aResponse().withStatus(status)));
    }

    @Test
    void confirmsOrderWhenStockIsReserved() throws Exception {
        stubReserve(201);

        mockMvc.perform(createOrder("IPHONE-15", 2))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        inventory.verify(postRequestedFor(urlEqualTo("/api/v1/reservations"))
                .withRequestBody(matchingJsonPath("$.sku", equalTo("IPHONE-15")))
                .withRequestBody(matchingJsonPath("$.orderRef")));
    }

    @Test
    void rejectsOrderWhenOutOfStockWithoutRetrying() throws Exception {
        stubReserve(409);

        mockMvc.perform(createOrder("PS5-SLIM", 50))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.failureReason").value("Insufficient stock for PS5-SLIM"));

        inventory.verify(1, postRequestedFor(urlEqualTo("/api/v1/reservations")));
    }

    @Test
    void retriesTransientInventoryErrors() throws Exception {
        inventory.stubFor(post(urlEqualTo("/api/v1/reservations")).inScenario("flaky")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("recovered"));
        inventory.stubFor(post(urlEqualTo("/api/v1/reservations")).inScenario("flaky")
                .whenScenarioStateIs("recovered")
                .willReturn(aResponse().withStatus(201)));

        mockMvc.perform(createOrder("PIXEL-9", 1))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        inventory.verify(2, postRequestedFor(urlEqualTo("/api/v1/reservations")));
    }

    @Test
    void returns503AndMarksOrderFailedWhenInventoryIsDown() throws Exception {
        stubReserve(500);

        String body = mockMvc.perform(createOrder("PIXEL-9", 1))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.title").value("Inventory unavailable"))
                .andReturn().getResponse().getContentAsString();
        // 3 attempts = 1 call + 2 retries
        inventory.verify(3, postRequestedFor(urlEqualTo("/api/v1/reservations")));

        Number orderId = JsonPath.read(body, "$.orderId");
        mockMvc.perform(get("/api/v1/orders/" + orderId))
                .andExpect(jsonPath("$.status").value("FAILED"));
    }

    @Test
    void circuitBreakerOpensAndFailsFast() throws Exception {
        stubReserve(500);

        // 2 failing orders x 3 attempts = 6 recorded failures >= minimum-number-of-calls (5)
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(createOrder("PIXEL-9", 1)).andExpect(status().isServiceUnavailable());
        }
        assertThat(circuitBreakerRegistry.circuitBreaker("inventory").getState())
                .isEqualTo(CircuitBreaker.State.OPEN);

        inventory.resetRequests();
        mockMvc.perform(createOrder("PIXEL-9", 1)).andExpect(status().isServiceUnavailable());
        // open breaker: inventory-service is not called at all
        inventory.verify(0, postRequestedFor(urlEqualTo("/api/v1/reservations")));
    }

    @Test
    void idempotencyKeyPreventsDuplicateOrders() throws Exception {
        stubReserve(201);

        String first = mockMvc.perform(createOrder("IPHONE-15", 1).header("Idempotency-Key", "checkout-42"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String second = mockMvc.perform(createOrder("IPHONE-15", 1).header("Idempotency-Key", "checkout-42"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(JsonPath.<Integer>read(second, "$.id")).isEqualTo(JsonPath.<Integer>read(first, "$.id"));
        inventory.verify(1, postRequestedFor(urlEqualTo("/api/v1/reservations")));
    }

    @Test
    void cancelReleasesReservedStock() throws Exception {
        stubReserve(201);
        inventory.stubFor(delete(urlPathMatching("/api/v1/reservations/.*")).willReturn(aResponse().withStatus(204)));

        String body = mockMvc.perform(createOrder("IPHONE-15", 1))
                .andReturn().getResponse().getContentAsString();
        Number id = JsonPath.read(body, "$.id");
        String orderRef = JsonPath.read(body, "$.orderRef");

        mockMvc.perform(MockMvcRequestBuilders.delete("/api/v1/orders/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        inventory.verify(deleteRequestedFor(urlEqualTo("/api/v1/reservations/" + orderRef)));

        // cancelling twice is a conflict, not a second release
        mockMvc.perform(MockMvcRequestBuilders.delete("/api/v1/orders/" + id))
                .andExpect(status().isConflict());
    }

    @Test
    void validatesRequests() throws Exception {
        mockMvc.perform(createOrder("", 0))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0]").value("quantity: must be greater than or equal to 1"))
                .andExpect(jsonPath("$.errors[1]").value("sku: must not be blank"));
        mockMvc.perform(get("/api/v1/orders?size=1000"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/orders/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Order not found"));
    }

    @Test
    void listsOrdersNewestFirst() throws Exception {
        stubReserve(201);
        mockMvc.perform(createOrder("AIRPODS-PRO", 1));
        mockMvc.perform(createOrder("AIRPODS-PRO", 2));

        mockMvc.perform(get("/api/v1/orders?size=1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].quantity").value(2))
                .andExpect(jsonPath("$.page.size").value(1));
    }

    @Test
    void readinessDoesNotDependOnInventory() throws Exception {
        stubReserve(500);
        mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
    }
}
