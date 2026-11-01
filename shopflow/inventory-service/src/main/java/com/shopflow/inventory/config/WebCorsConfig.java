package com.shopflow.inventory.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS for the local React lab-console (labs/lab-console.html). DEV ONLY: inventory-service has no
 * auth (internal), so the console can call it directly for the reservation-idempotency lab. Default
 * allows only localhost origins; override with APP_CORS_ALLOWED_ORIGIN_PATTERNS.
 */
@Configuration
public class WebCorsConfig implements WebMvcConfigurer {

    private final String[] originPatterns;

    public WebCorsConfig(
            @Value("${app.cors.allowed-origin-patterns:http://localhost:*,http://127.0.0.1:*}") String[] originPatterns) {
        this.originPatterns = originPatterns;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns(originPatterns)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
    }
}
