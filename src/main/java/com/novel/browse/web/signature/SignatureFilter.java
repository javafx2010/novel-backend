package com.novel.browse.web.signature;

import com.novel.browse.cache.CacheNames;
import com.novel.browse.config.SignatureProperties;
import com.novel.browse.i18n.Messages;
import com.novel.browse.web.dto.ApiError;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.LocaleResolver;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.Locale;

/**
 * Verifies the request signature {@code HMAC-SHA256(secret, canonical)}.
 *
 * <p>Required headers: {@code X-App-Key}, {@code X-Timestamp} (epoch seconds),
 * {@code X-Nonce}, {@code X-Signature}.
 *
 * <p>Skipped when: signature is disabled / secret blank, the request is a CORS preflight
 * (OPTIONS), or the path matches {@code app.signature.exclude-paths}.
 *
 * <p>Multipart uploads (EPUB files) are signed with the empty-body digest, because the body
 * is streamed by the controller and cannot be buffered cheaply.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class SignatureFilter extends OncePerRequestFilter {

    public static final String HEADER_APP_KEY = "X-App-Key";
    public static final String HEADER_TIMESTAMP = "X-Timestamp";
    public static final String HEADER_NONCE = "X-Nonce";
    public static final String HEADER_SIGNATURE = "X-Signature";

    private static final Logger log = LoggerFactory.getLogger(SignatureFilter.class);
    private static final int MAX_NONCE_LENGTH = 128;

    private final SignatureProperties properties;
    private final CacheManager cacheManager;
    private final Messages messages;
    private final LocaleResolver localeResolver;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public SignatureFilter(SignatureProperties properties, CacheManager cacheManager,
                           Messages messages, LocaleResolver localeResolver) {
        this.properties = properties;
        this.cacheManager = cacheManager;
        this.messages = messages;
        this.localeResolver = localeResolver;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!properties.active()) {
            return true;
        }
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String uri = request.getRequestURI();
        for (String pattern : properties.getExcludePaths()) {
            if (pathMatcher.match(pattern, uri)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        byte[] body = isMultipart(request) ? new byte[0] : request.getInputStream().readAllBytes();
        Rejection rejection = verify(request, body);
        if (rejection != null) {
            log.warn("Signature rejected: {} {} ({})", request.getMethod(), request.getRequestURI(), rejection.code());
            writeUnauthorized(response, request, rejection);
            return;
        }
        if (isMultipart(request)) {
            chain.doFilter(request, response);
        } else {
            chain.doFilter(new CachedBodyRequestWrapper(request, body), response);
        }
    }

    /** Returns the code of the rejection reason, or {@code null} when the request is accepted */
    private Rejection verify(HttpServletRequest request, byte[] body) {
        String appKey = request.getHeader(HEADER_APP_KEY);
        String timestamp = request.getHeader(HEADER_TIMESTAMP);
        String nonce = request.getHeader(HEADER_NONCE);
        String signature = request.getHeader(HEADER_SIGNATURE);

        if (isBlank(appKey) && !isBlank(properties.getAppKey())) {
            return new Rejection("error.signature.missingHeader", HEADER_APP_KEY);
        }
        if (isBlank(timestamp) || isBlank(nonce) || isBlank(signature)) {
            return new Rejection("error.signature.missingHeader", headerOf(timestamp, nonce, signature));
        }
        if (!isBlank(properties.getAppKey()) && !properties.getAppKey().equals(appKey)) {
            return new Rejection("error.signature.invalidAppKey", HEADER_APP_KEY);
        }
        long ts;
        try {
            ts = parseTimestamp(timestamp.trim());
        } catch (NumberFormatException e) {
            return new Rejection("error.signature.invalidTimestamp", HEADER_TIMESTAMP);
        }
        long skew = Math.abs(System.currentTimeMillis() / 1000 - ts);
        if (skew > properties.getTimeWindowSeconds()) {
            return new Rejection("error.signature.timestampOutOfWindow", properties.getTimeWindowSeconds());
        }
        if (nonce.length() > MAX_NONCE_LENGTH) {
            return new Rejection("error.signature.invalidNonce", HEADER_NONCE);
        }

        String expected = SignatureVerifier.hmacSha256Hex(properties.getSecret(),
                SignatureVerifier.canonical(request.getMethod(), request.getRequestURI(),
                        String.valueOf(ts), nonce, SignatureVerifier.sha256Hex(body)));
        if (!SignatureVerifier.matches(expected, signature.trim().toLowerCase(Locale.ROOT))) {
            return new Rejection("error.signature.invalid");
        }
        if (!registerNonce(nonce)) {
            return new Rejection("error.signature.replayed");
        }
        return null;
    }

    /** Stores the nonce for the configured window; {@code false} means it was already used */
    private boolean registerNonce(String nonce) {
        Cache cache = cacheManager.getCache(CacheNames.SIGNATURE_NONCE);
        if (cache == null) {
            log.debug("Nonce cache {} is unavailable, replay protection disabled", CacheNames.SIGNATURE_NONCE);
            return true;
        }
        if (cache.get(nonce) != null) {
            return false;
        }
        cache.put(nonce, Instant.now().toString());
        return true;
    }

    /** Same shape as {@link ApiError}, written by hand to stay free of a JSON library */
    private void writeUnauthorized(HttpServletResponse response, HttpServletRequest request, Rejection rejection)
            throws IOException {
        String uri = request.getRequestURI();
        // The filter runs before the DispatcherServlet sets the locale, so it is resolved from the request
        String message = messages.get(localeResolver.resolveLocale(request), rejection.code(), rejection.args());
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(String.format(Locale.ROOT,
                "{\"timestamp\":\"%s\",\"status\":%d,\"error\":\"Unauthorized\",\"message\":\"%s\",\"path\":\"%s\"}",
                Instant.now(), HttpStatus.UNAUTHORIZED.value(), jsonEscape(message), jsonEscape(uri)));
    }

    /** Rejection reason: a message code plus its arguments, resolved in the language of the request */
    private record Rejection(String code, Object... args) {
    }

    private static String jsonEscape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    private static String headerOf(String timestamp, String nonce, String signature) {
        if (isBlank(timestamp)) {
            return HEADER_TIMESTAMP;
        }
        if (isBlank(nonce)) {
            return HEADER_NONCE;
        }
        return HEADER_SIGNATURE;
    }

    /** Accepts epoch seconds and epoch milliseconds */
    private static long parseTimestamp(String value) {
        long parsed = Long.parseLong(value);
        return parsed > 100_000_000_000L ? parsed / 1000 : parsed;
    }

    private static boolean isMultipart(HttpServletRequest request) {
        String type = request.getContentType();
        return type != null && type.toLowerCase(Locale.ROOT).startsWith("multipart/");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** Replays the body already consumed by the signature check */
    private static final class CachedBodyRequestWrapper extends HttpServletRequestWrapper {

        private final byte[] body;

        private CachedBodyRequestWrapper(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream source = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return source.read();
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    return source.read(b, off, len);
                }

                @Override
                public int available() {
                    return source.available();
                }

                @Override
                public boolean isFinished() {
                    return source.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException("Read listeners are not supported");
                }
            };
        }

        @Override
        public java.io.BufferedReader getReader() {
            return new java.io.BufferedReader(new java.io.InputStreamReader(
                    new ByteArrayInputStream(body), java.nio.charset.StandardCharsets.UTF_8));
        }
    }
}
