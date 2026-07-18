package com.pdfconduit.web.config;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Marks the application's own MVC handler mapping ({@code requestMappingHandlerMapping}) as the
 * primary {@code RequestMappingHandlerMapping} bean.
 *
 * <p>Adding the actuator introduces a second bean of that type — actuator's
 * {@code controllerEndpointHandlerMapping} (which extends {@code RequestMappingHandlerMapping}).
 * In production this lives in the separate management-port context, but under a MOCK
 * {@code @SpringBootTest} both collapse into one context, so an inject-by-type of
 * {@code RequestMappingHandlerMapping} would become ambiguous. Flagging the application's mapping
 * primary keeps such injection resolving to the real request-routing table (the intent), in both
 * the test context and any same-port setup, without touching test code. No-op if the bean is absent.
 */
@Configuration
public class HandlerMappingPrimaryConfig {

    @Bean
    static BeanFactoryPostProcessor primaryRequestMappingHandlerMapping() {
        return (ConfigurableListableBeanFactory beanFactory) -> {
            if (beanFactory.containsBeanDefinition("requestMappingHandlerMapping")) {
                beanFactory.getBeanDefinition("requestMappingHandlerMapping").setPrimary(true);
            }
        };
    }
}
