package com.novel.browse.config;

import com.novel.browse.web.UnauthorizedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Simple authentication for admin APIs: when {@code app.admin.token} is configured, requests must carry {@code X-Admin-Token}.
 * No check is performed when it is not configured (convenient for local development); configure it in production.
 */
public class AdminTokenInterceptor implements HandlerInterceptor {

    static final String HEADER = "X-Admin-Token";

    private final String token;

    public AdminTokenInterceptor(String token) {
        this.token = token == null ? "" : token.trim();
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (token.isEmpty() || "OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String provided = request.getHeader(HEADER);
        if (provided == null || !token.equals(provided.trim())) {
            throw new UnauthorizedException("Missing or invalid " + HEADER);
        }
        return true;
    }
}
