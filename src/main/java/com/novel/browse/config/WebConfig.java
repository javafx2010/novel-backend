package com.novel.browse.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Separated frontend and backend: configures CORS so a standalone frontend (Vite etc.) can call this service directly.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${app.cors.allowed-origins:*}")
    private String[] allowedOrigins;

    @Value("${app.cors.allowed-methods:GET,POST,PUT,DELETE,OPTIONS}")
    private String[] allowedMethods;

    @Value("${app.cors.allow-credentials:false}")
    private boolean allowCredentials;

    /** Admin API token, blank means no check */
    @Value("${app.admin.token:}")
    private String adminToken;

    private static final Logger log = LoggerFactory.getLogger(WebConfig.class);

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        if (adminToken == null || adminToken.isBlank()) {
            log.warn("app.admin.token is not configured, admin APIs (/admin/**) are not authenticated; configure it in production");
        }
        registry.addInterceptor(new AdminTokenInterceptor(adminToken))
                .addPathPatterns("/admin/**");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (allowedOrigins == null || allowedOrigins.length == 0) {
            return;
        }
        registry.addMapping("/**")
                .allowedOriginPatterns(allowedOrigins)
                .allowedMethods(allowedMethods)
                .allowedHeaders("*")
                .allowCredentials(allowCredentials)
                .maxAge(3600);
    }
}
