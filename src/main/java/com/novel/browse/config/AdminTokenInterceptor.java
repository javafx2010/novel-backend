package com.novel.browse.config;

import com.novel.browse.web.UnauthorizedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 后台管理接口的简易鉴权：配置 {@code app.admin.token} 后，请求需携带 {@code X-Admin-Token}。
 * 未配置时不做校验（本地开发便利），生产建议配置。
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
            throw new UnauthorizedException("缺少或错误的 " + HEADER);
        }
        return true;
    }
}
