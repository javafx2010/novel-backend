package com.novel.browse.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * MCP (Model Context Protocol) endpoint settings, prefix {@code app.mcp}.
 *
 * <p>The endpoint is a plain JSON-RPC 2.0 service at {@code POST /mcp}: no SDK dependency,
 * it exposes the {@code upload_epub} / {@code update_novel} / {@code list_categories} / {@code list_novels} tools.
 */
@Component
@ConfigurationProperties(prefix = "app.mcp")
public class McpProperties {

    /** Master switch: false removes the /mcp endpoint entirely */
    private boolean enabled = true;

    /**
     * Optional token for {@code /mcp}, sent as {@code Authorization: Bearer <token>} or {@code X-Mcp-Token}.
     * When blank it falls back to {@code app.admin.token}; both blank means no check.
     */
    private String token = "";

    /** Server name reported in the initialize result */
    private String serverName = "novel-browse";

    /** Server version reported in the initialize result */
    private String serverVersion = "0.0.1";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getServerName() {
        return serverName;
    }

    public void setServerName(String serverName) {
        this.serverName = serverName;
    }

    public String getServerVersion() {
        return serverVersion;
    }

    public void setServerVersion(String serverVersion) {
        this.serverVersion = serverVersion;
    }
}
