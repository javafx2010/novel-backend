package com.novel.browse.web.mcp;

import com.novel.browse.config.McpProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * MCP endpoint over Streamable HTTP: {@code POST /mcp} takes JSON-RPC 2.0 requests and answers with a
 * single JSON response ({@code GET /mcp} opens an SSE stream, {@code DELETE /mcp} closes a session).
 * <p>
 * Access is protected by {@code app.mcp.token}, falling back to {@code app.admin.token}; both blank means open.
 */
@RestController
@RequestMapping("/mcp")
@ConditionalOnProperty(name = "app.mcp.enabled", havingValue = "true", matchIfMissing = true)
public class McpController {

    private static final Logger log = LoggerFactory.getLogger(McpController.class);
    private static final long SSE_TIMEOUT_MS = 300_000L;

    private final McpServer server;
    private final McpProperties properties;
    /** Fallback token: the MCP endpoint performs admin operations, so the admin token applies by default */
    private final String adminToken;

    public McpController(McpServer server, McpProperties properties,
                         @Value("${app.admin.token:}") String adminToken) {
        this.server = server;
        this.properties = properties;
        this.adminToken = adminToken;
        if (requiredToken() == null) {
            log.warn("No MCP token configured (app.mcp.token / app.admin.token), /mcp is open to anyone");
        }
    }

    /** Handles one JSON-RPC message; notifications (no id) answer 202 without a body */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> handle(@RequestBody String body, HttpServletRequest request) {
        String token = requiredToken();
        if (token != null && !token.equals(requestToken(request))) {
            log.warn("Rejected an MCP request with a missing or invalid token from {}", request.getRemoteAddr());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body("{\"error\":\"Unauthorized\",\"message\":\"Missing or invalid MCP token"
                            + " (Authorization: Bearer <token> or X-Mcp-Token)\"}");
        }
        String response = server.handle(body);
        return response == null ? ResponseEntity.accepted().build()
                : ResponseEntity.ok().header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE).body(response);
    }

    /** Server-to-client stream: this server only sends keep-alive comments, so the stream stays empty */
    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        emitter.onTimeout(emitter::complete);
        emitter.onError(e -> emitter.complete());
        try {
            emitter.send(SseEmitter.event().comment("mcp stream connected"));
        } catch (Exception e) {
            emitter.complete();
        }
        return emitter;
    }

    /** Session termination: the server is stateless, so this only acknowledges the request */
    @DeleteMapping
    public ResponseEntity<Void> terminate() {
        return ResponseEntity.noContent().build();
    }

    /** Configured token, null when the endpoint is open */
    private String requiredToken() {
        String token = properties.getToken();
        if (token != null && !token.isBlank()) {
            return token.trim();
        }
        return adminToken == null || adminToken.isBlank() ? null : adminToken.trim();
    }

    private String requestToken(HttpServletRequest request) {
        String header = request.getHeader("X-Mcp-Token");
        if (header != null && !header.isBlank()) {
            return header.trim();
        }
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return authorization.substring(7).trim();
        }
        return null;
    }
}
