package com.pdfconduit.web.config;

import com.pdfconduit.web.quota.QuotaInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers the free-tier {@link QuotaInterceptor} on {@code /api/**}. It runs after the
 * (servlet-level) rate-limit filter and before controllers, matching the required hardening order
 * rate-limit → quota → (per-endpoint) load-guard/timeout.
 */
@Configuration
public class InterceptorConfig implements WebMvcConfigurer {

    private final QuotaInterceptor quotaInterceptor;

    public InterceptorConfig(QuotaInterceptor quotaInterceptor) {
        this.quotaInterceptor = quotaInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(quotaInterceptor).addPathPatterns("/api/**");
    }
}
