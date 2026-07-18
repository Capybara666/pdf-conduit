package com.pdfconduit.web.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Enables CORS for the configured frontend origins (default {@code http://localhost:4200}),
 * so the Angular app can call {@code /api/**} directly during {@code ng serve} without a proxy.
 * Only the verbs the API uses (GET, POST) are allowed.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    private final String[] allowedOrigins;

    public CorsConfig(WebProperties props) {
        this.allowedOrigins = props.cors().allowedOrigins().toArray(new String[0]);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
            .allowedOrigins(allowedOrigins)
            .allowedMethods("GET", "POST")
            .allowedHeaders("*")
            .exposedHeaders("Content-Disposition", "X-Target-Reached", "X-Original-Bytes",
                "X-Result-Bytes", "X-Batch-Failures",
                "X-RateLimit-Limit", "X-RateLimit-Remaining", "Retry-After",
                "X-Quota-Limit", "X-Quota-Remaining", "X-Quota-Reset")
            .maxAge(3600);
    }
}
