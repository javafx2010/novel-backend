package com.novel.browse.web.mcp;

import com.novel.browse.config.McpProperties;
import com.novel.browse.i18n.Messages;
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
 * Tools: {@code upload_epub} (import an EPUB, one edition per language), {@code update_novel} (edit metadata),
 * {@code delete_novel} (remove one edition or an entire work), {@code list_categories} and {@code list_novels}
 * (look up ids before calling the write tools), {@code list_novel_versions} (every language version of one work).
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
    private final Messages messages;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, McpTool> tools = new LinkedHashMap<>();

    public McpServer(AdminService adminService,
                     NovelService novelService,
                     McpProperties properties,
                     Messages messages) {
        this.adminService = adminService;
        this.novelService = novelService;
        this.properties = properties;
        this.messages = messages;

        register(new McpTool("upload_epub", "mcp.tool.upload.description",
                this::uploadSchema, this::uploadEpub));
        register(new McpTool("update_novel", "mcp.tool.update.description",
                this::updateSchema, this::updateNovel));
        register(new McpTool("delete_novel", "mcp.tool.delete.description",
                this::deleteSchema, this::deleteNovel));
        register(new McpTool("list_categories", "mcp.tool.listCategories.description",
                this::listCategoriesSchema, this::listCategories));
        register(new McpTool("list_novels", "mcp.tool.listNovels.description",
                this::listNovelsSchema, this::listNovels));
        register(new McpTool("list_novel_versions", "mcp.tool.listVersions.description",
                this::listVersionsSchema, this::listNovelVersions));
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
            return error(null, -32700, messages.get("error.mcp.parseError", e.getMessage()));
        }
        if (request == null || !request.isObject()) {
            return error(null, -32700, messages.get("error.mcp.parseErrorNotObject"));
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
            return error(id, -32601, messages.get("error.mcp.methodNotFound", method));
        } catch (Exception e) {
            log.warn("MCP {} failed: {}", method, e.getMessage());
            return error(id, -32603, messages.get("error.mcp.internalError", e.getMessage()));
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
        result.put("instructions", messages.get("mcp.instructions"));
        return result;
    }

    private ObjectNode toolsList() {
        ArrayNode array = mapper.createArrayNode();
        for (McpTool tool : tools.values()) {
            ObjectNode item = array.addObject();
            item.put("name", tool.name());
            item.put("description", messages.get(tool.descriptionCode()));
            item.set("inputSchema", tool.inputSchema().build(messages));
        }
        ObjectNode result = mapper.createObjectNode();
        result.set("tools", array);
        return result;
    }

    private ObjectNode callTool(JsonNode params) {
        String name = params == null ? "" : params.path("name").asString("");
        McpTool tool = tools.get(name);
        if (tool == null) {
            return toolError(messages.get("error.mcp.unknownTool", name, String.join(", ", tools.keySet())));
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
            return toolResult(messages.of(e), null, true);
        } catch (Exception e) {
            log.warn("MCP tool {} failed: {}", name, e.getMessage());
            return toolResult(messages.get("error.mcp.toolFailed", name, e.getMessage()), null, true);
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
            throw new BadRequestException("error.mcp.fileNameRequired");
        }
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(".epub")) {
            throw new BadRequestException("error.upload.onlyEpub", fileName);
        }
        if (base64 == null || base64.isBlank()) {
            throw new BadRequestException("error.mcp.contentRequired");
        }
        byte[] data;
        try {
            data = Base64.getDecoder().decode(base64.trim());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("error.mcp.invalidBase64", e.getMessage());
        }
        if (data.length == 0) {
            throw new BadRequestException("error.mcp.emptyContent");
        }
        Long categoryId = args.hasNonNull("categoryId") ? args.get("categoryId").asLong() : null;
        Novel novel = adminService.upload(data, fileName, categoryId, text(args, "categoryName"),
                text(args, "categoryDescription"), text(args, "status"), text(args, "tags"),
                text(args, "language"), text(args, "versionGroup"));
        return novelNode(novel);
    }

    private ObjectNode updateNovel(JsonNode args) {
        long id = args.path("id").asLong(-1L);
        if (id <= 0) {
            throw new BadRequestException("error.mcp.idRequired");
        }
        Long categoryId = args.hasNonNull("categoryId") ? args.get("categoryId").asLong() : null;
        Novel novel = adminService.updateNovel(id, text(args, "title"), text(args, "author"),
                text(args, "description"), text(args, "status"), text(args, "tags"), categoryId,
                text(args, "language"));
        return novelNode(novel);
    }

    /** Categories of one language; the language field of each one tells which library it belongs to */
    private ObjectNode listCategories(JsonNode args) {
        ArrayNode array = mapper.createArrayNode();
        for (Category category : novelService.listCategories(text(args, "language"))) {
            ObjectNode item = array.addObject();
            item.put("id", category.getId());
            item.put("name", category.getName());
            item.put("language", category.getLanguage());
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
        adminService.listNovels(text(args, "language")).stream().limit(limit)
                .forEach(novel -> array.add(novelNode(novel)));
        ObjectNode result = mapper.createObjectNode();
        result.put("count", array.size());
        result.set("novels", array);
        return result;
    }

    /** Every language version of one work, the requested edition included */
    private ObjectNode listNovelVersions(JsonNode args) {
        long id = args.path("id").asLong(-1L);
        if (id <= 0) {
            throw new BadRequestException("error.mcp.idRequired");
        }
        ArrayNode array = mapper.createArrayNode();
        novelService.versions(id).forEach(novel -> array.add(novelNode(novel)));
        ObjectNode result = mapper.createObjectNode();
        result.put("count", array.size());
        result.set("versions", array);
        return result;
    }

    /**
     * Deletes one edition. With {@code deleteAllVersions=true} the entire work (every language version of the
     * same {@code version_group}) is removed.
     */
    private ObjectNode deleteNovel(JsonNode args) {
        long id = args.path("id").asLong(-1L);
        if (id <= 0) {
            throw new BadRequestException("error.mcp.idRequired");
        }
        boolean allVersions = args.path("deleteAllVersions").asBoolean(false);
        List<Novel> targets = allVersions ? novelService.versions(id) : List.of(novelService.detail(id));
        ArrayNode ids = mapper.createArrayNode();
        for (Novel target : targets) {
            adminService.deleteNovel(target.getId());
            ids.add(target.getId());
        }
        ObjectNode result = mapper.createObjectNode();
        result.put("deleted", ids.size());
        result.set("ids", ids);
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

    /**
     * A novel as JSON: the public fields plus {@code versionGroup}, so a caller can upload another
     * translation of the same work with {@code upload_epub(versionGroup: ...)}.
     */
    private ObjectNode novelNode(Novel novel) {
        ObjectNode node = toNode(NovelSummary.of(novel));
        if (novel.getVersionGroup() != null && !novel.getVersionGroup().isBlank()) {
            node.put("versionGroup", novel.getVersionGroup());
        }
        return node;
    }

    private String text(JsonNode args, String field) {
        JsonNode node = args.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asString("");
        return value.isBlank() ? null : value.trim();
    }

    private ObjectNode emptySchema(Messages messages) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties");
        return schema;
    }

    private ObjectNode listCategoriesSchema(Messages messages) {
        ObjectNode properties = mapper.createObjectNode();
        properties.set("language", stringProperty(messages, "mcp.schema.listCategories.language"));
        return schema(properties, List.of());
    }

    private ObjectNode uploadSchema(Messages messages) {
        ObjectNode properties = mapper.createObjectNode();
        properties.set("fileName", stringProperty(messages, "mcp.schema.upload.fileName"));
        properties.set("contentBase64", stringProperty(messages, "mcp.schema.upload.contentBase64"));
        properties.set("categoryId", integerProperty(messages, "mcp.schema.upload.categoryId"));
        properties.set("categoryName", stringProperty(messages, "mcp.schema.upload.categoryName"));
        properties.set("categoryDescription", stringProperty(messages, "mcp.schema.upload.categoryDescription"));
        properties.set("status", stringProperty(messages, "mcp.schema.upload.status"));
        properties.set("tags", stringProperty(messages, "mcp.schema.upload.tags"));
        properties.set("language", stringProperty(messages, "mcp.schema.upload.language"));
        properties.set("versionGroup", stringProperty(messages, "mcp.schema.upload.versionGroup"));
        return schema(properties, List.of("fileName", "contentBase64"));
    }

    private ObjectNode updateSchema(Messages messages) {
        ObjectNode properties = mapper.createObjectNode();
        properties.set("id", integerProperty(messages, "mcp.schema.update.id"));
        properties.set("title", stringProperty(messages, "mcp.schema.update.title"));
        properties.set("author", stringProperty(messages, "mcp.schema.update.author"));
        properties.set("description", stringProperty(messages, "mcp.schema.update.description"));
        properties.set("status", stringProperty(messages, "mcp.schema.update.status"));
        properties.set("tags", stringProperty(messages, "mcp.schema.update.tags"));
        properties.set("language", stringProperty(messages, "mcp.schema.update.language"));
        properties.set("categoryId", integerProperty(messages, "mcp.schema.update.categoryId"));
        return schema(properties, List.of("id"));
    }

    private ObjectNode listNovelsSchema(Messages messages) {
        ObjectNode properties = mapper.createObjectNode();
        properties.set("limit", integerProperty(messages, "mcp.schema.listNovels.limit"));
        properties.set("language", stringProperty(messages, "mcp.schema.listNovels.language"));
        return schema(properties, List.of());
    }

    private ObjectNode listVersionsSchema(Messages messages) {
        ObjectNode properties = mapper.createObjectNode();
        properties.set("id", integerProperty(messages, "mcp.schema.listVersions.id"));
        return schema(properties, List.of("id"));
    }

    private ObjectNode deleteSchema(Messages messages) {
        ObjectNode properties = mapper.createObjectNode();
        properties.set("id", integerProperty(messages, "mcp.schema.delete.id"));
        properties.set("deleteAllVersions", booleanProperty(messages, "mcp.schema.delete.deleteAllVersions"));
        return schema(properties, List.of("id"));
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

    private ObjectNode stringProperty(Messages messages, String descriptionCode) {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", "string");
        node.put("description", messages.get(descriptionCode));
        return node;
    }

    private ObjectNode integerProperty(Messages messages, String descriptionCode) {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", "integer");
        node.put("description", messages.get(descriptionCode));
        return node;
    }

    private ObjectNode booleanProperty(Messages messages, String descriptionCode) {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", "boolean");
        node.put("description", messages.get(descriptionCode));
        return node;
    }

    /**
     * One exposed MCP tool: name, description code (resolved per request locale), schema builder
     * (also locale aware) and the handler
     */
    public record McpTool(String name, String descriptionCode, SchemaFactory inputSchema, ToolHandler handler) {

    }

    /** Builds the JSON schema of a tool's arguments in the requested language */
    public interface SchemaFactory {

        ObjectNode build(Messages messages);
    }

    /** Executes a tool and returns its structured result */
    public interface ToolHandler {

        ObjectNode call(JsonNode arguments);
    }
}
