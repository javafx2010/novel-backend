package com.novel.browse.web.mcp;

import com.novel.browse.config.McpProperties;
import com.novel.browse.domain.Category;
import com.novel.browse.domain.Novel;
import com.novel.browse.service.AdminService;
import com.novel.browse.service.BadRequestException;
import com.novel.browse.service.NotFoundException;
import com.novel.browse.service.NovelService;
import com.novel.browse.web.dto.NovelSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Minimal MCP server over JSON-RPC 2.0 (Streamable HTTP): {@code initialize}, {@code tools/list},
 * {@code tools/call} plus the empty {@code resources/list} / {@code prompts/list} responses.
 * <p>
 * The protocol is small enough to implement directly, so this stays free of an MCP SDK dependency.
 * Tools: {@code upload_epub} (import an EPUB), {@code update_novel} (edit metadata),
 * {@code list_categories} and {@code list_novels} (look up ids before calling the two write tools).
 */
@Service
public class McpServer {

    /** Default protocol version reported to clients that do not send one */
    private static final String DEFAULT_PROTOCOL_VERSION = "2025-06-18";
    private static final List<String> KNOWN_PROTOCOL_VERSIONS =
            List.of("2024-11-05", "2025-03-26", "2025-06-18");
    private static final int MAX_LIST_LIMIT = 100;

    private static final Logger log = LoggerFactory.getLogger(McpServer.class);

    private final AdminService adminService;
    private final NovelService novelService;
    private final McpProperties properties;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, McpTool> tools = new LinkedHashMap<>();

    public McpServer(AdminService adminService,
                     NovelService novelService,
                     McpProperties properties) {
        this.adminService = adminService;
        this.novelService = novelService;
        this.properties = properties;

        register(new McpTool("upload_epub",
                "Import an EPUB book: parses chapters, extracts the cover and creates the novel. "
                        + "Pass the raw file as base64. Duplicate content (same SHA-256) is rejected.",
                uploadSchema(), this::uploadEpub));
        register(new McpTool("update_novel",
                "Update the metadata of an existing novel (title, author, description, status, tags, category). "
                        + "Only the provided fields are changed.",
                updateSchema(), this::updateNovel));
        register(new McpTool("list_categories",
                "List all categories with their ids; call this before upload_epub when the category id is unknown.",
                emptySchema(), this::listCategories));
        register(new McpTool("list_novels",
                "List novels (newest update first) with their ids; call this before update_novel when the id is unknown.",
                listNovelsSchema(), this::listNovels));
    }

    /** Tools exposed by {@code tools/list} */
    public List<McpTool> tools() {
        return List.copyOf(tools.values());
    }

    /**
     * Handles one JSON-RPC message.
     *
     * @param body raw request body
     * @return the response body, or {@code null} for notifications (no response expected)
     */
    public String handle(String body) {
        JsonNode request;
        try {
            request = mapper.readTree(body == null ? "" : body);
        } catch (Exception e) {
            return error(null, -32700, "Parse error: " + e.getMessage());
        }
        if (request == null || !request.isObject()) {
            return error(null, -32700, "Parse error: the request body must be a JSON object");
        }
        JsonNode id = request.get("id");
        String method = request.path("method").asString("");
        JsonNode params = request.get("params");
        boolean notification = id == null || id.isNull();

        try {
            if ("initialize".equals(method)) {
                return result(id, initializeResult(params));
            }
            if (method != null && method.startsWith("notifications/")) {
                return notification ? null : result(id, mapper.createObjectNode());
            }
            if ("ping".equals(method)) {
                return result(id, mapper.createObjectNode());
            }
            if ("tools/list".equals(method)) {
                return result(id, toolsList());
            }
            if ("tools/call".equals(method)) {
                return result(id, callTool(params));
            }
            if ("resources/list".equals(method)) {
                ObjectNode empty = mapper.createObjectNode();
                empty.putArray("resources");
                return result(id, empty);
            }
            if ("prompts/list".equals(method)) {
                ObjectNode empty = mapper.createObjectNode();
                empty.putArray("prompts");
                return result(id, empty);
            }
            return error(id, -32601, "Method not found: " + method);
        } catch (Exception e) {
            log.warn("MCP {} failed: {}", method, e.getMessage());
            return error(id, -32603, "Internal error: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // protocol messages
    // ------------------------------------------------------------------

    private ObjectNode initializeResult(JsonNode params) {
        String requested = params == null ? null : params.path("protocolVersion").asString(null);
        String version = requested != null && KNOWN_PROTOCOL_VERSIONS.contains(requested)
                ? requested : DEFAULT_PROTOCOL_VERSION;
        ObjectNode result = mapper.createObjectNode();
        result.put("protocolVersion", version);
        ObjectNode capabilities = result.putObject("capabilities");
        capabilities.putObject("tools").put("listChanged", false);
        ObjectNode serverInfo = result.putObject("serverInfo");
        serverInfo.put("name", properties.getServerName());
        serverInfo.put("version", properties.getServerVersion());
        result.put("instructions", "Novel browsing service. Use list_categories / list_novels to look up ids, "
                + "then upload_epub to import an EPUB (base64) or update_novel to edit a book's metadata.");
        return result;
    }

    private ObjectNode toolsList() {
        ArrayNode array = mapper.createArrayNode();
        for (McpTool tool : tools.values()) {
            ObjectNode item = array.addObject();
            item.put("name", tool.name());
            item.put("description", tool.description());
            item.set("inputSchema", tool.inputSchema());
        }
        ObjectNode result = mapper.createObjectNode();
        result.set("tools", array);
        return result;
    }

    private ObjectNode callTool(JsonNode params) {
        String name = params == null ? "" : params.path("name").asString("");
        McpTool tool = tools.get(name);
        if (tool == null) {
            return toolError("Unknown tool: " + name + ", available: " + String.join(", ", tools.keySet()));
        }
        JsonNode arguments = params == null ? null : params.get("arguments");
        if (arguments == null || arguments.isNull() || !arguments.isObject()) {
            arguments = mapper.createObjectNode();
        }
        try {
            ObjectNode payload = tool.handler().call(arguments);
            log.info("MCP tool called: {}", name);
            return toolResult(payload.toString(), payload, false);
        } catch (BadRequestException | NotFoundException e) {
            return toolResult(e.getMessage(), null, true);
        } catch (Exception e) {
            log.warn("MCP tool {} failed: {}", name, e.getMessage());
            return toolResult("Tool " + name + " failed: " + e.getMessage(), null, true);
        }
    }

    private ObjectNode toolResult(String text, ObjectNode structured, boolean isError) {
        ObjectNode result = mapper.createObjectNode();
        ArrayNode content = result.putArray("content");
        ObjectNode item = content.addObject();
        item.put("type", "text");
        item.put("text", text);
        if (structured != null) {
            result.set("structuredContent", structured);
        }
        result.put("isError", isError);
        return result;
    }

    private ObjectNode toolError(String message) {
        return toolResult(message, null, true);
    }

    private String result(JsonNode id, ObjectNode result) {
        ObjectNode response = mapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        if (id != null && !id.isNull()) {
            response.set("id", id);
        } else {
            response.putNull("id");
        }
        response.set("result", result);
        return write(response);
    }

    private String error(JsonNode id, int code, String message) {
        ObjectNode response = mapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        if (id != null && !id.isNull()) {
            response.set("id", id);
        } else {
            response.putNull("id");
        }
        ObjectNode error = response.putObject("error");
        error.put("code", code);
        error.put("message", message);
        return write(response);
    }

    private String write(ObjectNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (Exception e) {
            return "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32603,\"message\":\"Serialization failed\"}}";
        }
    }

    // ------------------------------------------------------------------
    // tools
    // ------------------------------------------------------------------

    private ObjectNode uploadEpub(JsonNode args) {
        String fileName = text(args, "fileName");
        String base64 = text(args, "contentBase64");
        if (fileName == null || fileName.isBlank()) {
            throw new BadRequestException("fileName is required");
        }
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(".epub")) {
            throw new BadRequestException("Only EPUB files (.epub) are supported: " + fileName);
        }
        if (base64 == null || base64.isBlank()) {
            throw new BadRequestException("contentBase64 is required (base64 encoded EPUB content)");
        }
        byte[] data;
        try {
            data = Base64.getDecoder().decode(base64.trim());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("contentBase64 is not valid base64: " + e.getMessage());
        }
        if (data.length == 0) {
            throw new BadRequestException("The decoded EPUB content is empty");
        }
        Long categoryId = args.hasNonNull("categoryId") ? args.get("categoryId").asLong() : null;
        Novel novel = adminService.upload(data, fileName, categoryId, text(args, "categoryName"),
                text(args, "status"), text(args, "tags"));
        return toNode(NovelSummary.of(novel));
    }

    private ObjectNode updateNovel(JsonNode args) {
        long id = args.path("id").asLong(-1L);
        if (id <= 0) {
            throw new BadRequestException("id is required (use list_novels to find it)");
        }
        Long categoryId = args.hasNonNull("categoryId") ? args.get("categoryId").asLong() : null;
        Novel novel = adminService.updateNovel(id, text(args, "title"), text(args, "author"),
                text(args, "description"), text(args, "status"), text(args, "tags"), categoryId);
        return toNode(NovelSummary.of(novel));
    }

    private ObjectNode listCategories(JsonNode args) {
        ArrayNode array = mapper.createArrayNode();
        for (Category category : novelService.listCategories()) {
            ObjectNode item = array.addObject();
            item.put("id", category.getId());
            item.put("name", category.getName());
            if (category.getDescription() != null && !category.getDescription().isBlank()) {
                item.put("description", category.getDescription());
            }
        }
        ObjectNode result = mapper.createObjectNode();
        result.set("categories", array);
        return result;
    }

    private ObjectNode listNovels(JsonNode args) {
        int limit = (int) Math.min(MAX_LIST_LIMIT, Math.max(1, args.path("limit").asLong(20)));
        ArrayNode array = mapper.createArrayNode();
        adminService.listNovels().stream().limit(limit)
                .forEach(novel -> array.add(toNode(NovelSummary.of(novel))));
        ObjectNode result = mapper.createObjectNode();
        result.put("count", array.size());
        result.set("novels", array);
        return result;
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private void register(McpTool tool) {
        tools.put(tool.name(), tool);
    }

    private ObjectNode toNode(Object value) {
        return (ObjectNode) mapper.valueToTree(value);
    }

    private String text(JsonNode args, String field) {
        JsonNode node = args.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asString("");
        return value.isBlank() ? null : value.trim();
    }

    private ObjectNode emptySchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties");
        return schema;
    }

    private ObjectNode uploadSchema() {
        ObjectNode properties = mapper.createObjectNode();
        properties.set("fileName", stringProperty("Original file name, must end with .epub"));
        properties.set("contentBase64", stringProperty("Base64 encoded content of the EPUB file"));
        properties.set("categoryId", integerProperty("Target category id; omit to use categoryName"));
        properties.set("categoryName", stringProperty("Target category name, created when it does not exist yet"
                + " (defaults to app.upload.default-category-name)"));
        properties.set("status", stringProperty("Serialization status: ONGOING or FINISHED, default ONGOING"));
        properties.set("tags", stringProperty("Comma separated tags; defaults to the EPUB dc:subject"));
        return schema(properties, List.of("fileName", "contentBase64"));
    }

    private ObjectNode updateSchema() {
        ObjectNode properties = mapper.createObjectNode();
        properties.set("id", integerProperty("Novel id to update (use list_novels to find it)"));
        properties.set("title", stringProperty("New title"));
        properties.set("author", stringProperty("New author"));
        properties.set("description", stringProperty("New description"));
        properties.set("status", stringProperty("New status: ONGOING or FINISHED"));
        properties.set("tags", stringProperty("New tags, comma separated"));
        properties.set("categoryId", integerProperty("Move the novel to this category id (must already exist)"));
        return schema(properties, List.of("id"));
    }

    private ObjectNode listNovelsSchema() {
        ObjectNode properties = mapper.createObjectNode();
        properties.set("limit", integerProperty("Maximum number of novels to return, 1-100, default 20"));
        return schema(properties, List.of());
    }

    private ObjectNode schema(ObjectNode properties, List<String> required) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", properties);
        if (!required.isEmpty()) {
            ArrayNode array = schema.putArray("required");
            required.forEach(array::add);
        }
        return schema;
    }

    private ObjectNode stringProperty(String description) {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", "string");
        node.put("description", description);
        return node;
    }

    private ObjectNode integerProperty(String description) {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", "integer");
        node.put("description", description);
        return node;
    }

    /** One exposed MCP tool: name, description, JSON schema of the arguments and the handler */
    public record McpTool(String name, String description, ObjectNode inputSchema, ToolHandler handler) {

    }

    /** Executes a tool and returns its structured result */
    public interface ToolHandler {

        ObjectNode call(JsonNode arguments);
    }
}
