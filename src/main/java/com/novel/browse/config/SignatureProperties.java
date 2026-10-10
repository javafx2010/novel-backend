package com.novel.browse.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * API signature settings, prefix {@code app.signature}.
 *
 * <p>Signature check is opt-in: it is skipped entirely when {@code secret} is blank,
 * so local development and the frontend keep working without any header.
 */
@Component
@ConfigurationProperties(prefix = "app.signature")
public class SignatureProperties {

    /** Master switch; the check also requires a non-blank secret */
    private boolean enabled = true;

    /** Application key sent as {@code X-App-Key}; compared when configured */
    private String appKey = "";

    /** Shared secret used as the HMAC-SHA256 key */
    private String secret = "";

    /** Allowed clock skew between client and server, in seconds */
    private long timeWindowSeconds = 300L;

    /** Paths excluded from signature check (Ant patterns, e.g. {@code /categories}) */
    private List<String> excludePaths = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getAppKey() {
        return appKey;
    }

    public void setAppKey(String appKey) {
        this.appKey = appKey;
    }

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public long getTimeWindowSeconds() {
        return timeWindowSeconds;
    }

    public void setTimeWindowSeconds(long timeWindowSeconds) {
        this.timeWindowSeconds = timeWindowSeconds;
    }

    public List<String> getExcludePaths() {
        return excludePaths;
    }

    public void setExcludePaths(List<String> excludePaths) {
        this.excludePaths = excludePaths;
    }

    /** Signature check actually takes effect only when enabled and a secret is set */
    public boolean active() {
        return enabled && secret != null && !secret.isBlank();
    }
}
