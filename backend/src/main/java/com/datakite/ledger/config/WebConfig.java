package com.datakite.ledger.config;

import com.datakite.ledger.interceptor.IdempotencyKeyInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final IdempotencyKeyInterceptor idempotencyKeyInterceptor;

    @Value("${app.cors.allowed-origins}")
    private String allowedOrigins;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(idempotencyKeyInterceptor)
                .addPathPatterns("/api/v1/ledger/transfer");
    }

    /**
     * exposedHeaders is required for Idempotent-Replay: custom response
     * headers aren't on the CORS safelist, so without this the browser
     * silently strips it and fetch()'s Headers.get() always returns null,
     * even though the server sent it.
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins.split(","))
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("Idempotent-Replay");
    }
}
