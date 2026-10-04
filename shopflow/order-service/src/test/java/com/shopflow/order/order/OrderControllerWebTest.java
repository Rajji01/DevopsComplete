package com.shopflow.order.order;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import com.shopflow.order.security.JwtRolesConverter;
import com.shopflow.order.security.SecurityConfig;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-layer slice: only the controller + advice are loaded, the service is mocked.
 * Used for error mappings that are hard to provoke through the full stack.
 */
@WebMvcTest(OrderController.class)
@Import({SecurityConfig.class, JwtRolesConverter.class})
class OrderControllerWebTest {

    @MockitoBean
    private JwtDecoder jwtDecoder; // never called: tests inject the JWT directly

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderService orderService;

    @Test
    void optimisticLockConflictIs409() throws Exception {
        when(orderService.cancel(anyLong()))
                .thenThrow(new ObjectOptimisticLockingFailureException(Order.class, 7L));

        mockMvc.perform(delete("/api/v1/orders/7").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_customer"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Concurrent modification"));
    }

    @Test
    void rateLimitExceededIs429() throws Exception {
        when(orderService.placeOrder(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.any()))
                .thenThrow(RequestNotPermitted.createRequestNotPermitted(RateLimiter.ofDefaults("orders")));

        mockMvc.perform(post("/api/v1/orders")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_customer")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sku":"IPHONE-15","quantity":1}"""))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.title").value("Too many requests"));
    }
}
