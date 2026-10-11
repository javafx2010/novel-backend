package com.novel.browse.web.mcp;

import com.novel.browse.config.McpProperties;
import com.novel.browse.domain.Category;
import com.novel.browse.repository.CategoryRepository;
import com.novel.browse.service.AdminService;
import com.novel.browse.service.NovelService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** JSON-RPC dispatch and tool argument checks of the MCP server (no Spring context, no HTTP) */
class McpServerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private AdminService adminService;
    private NovelService novelService;
    private CategoryRepository categoryRepository;
    private McpServer server;

    @BeforeEach
    void setUp() {
        adminService = mock(AdminService.class);
        novelService = mock(NovelService.class);
        categoryRepository = mock(CategoryRepository.class);
        server = new McpServer(adminService, novelService, categoryRepository, new McpProperties(), "Imported");
    }

    @Test
    void initializeRepliesWithProtocolVersionAndServerInfo() {
        JsonNode result = readResult(message("initialize", "{\"protocolVersion\":\"2025-06-18\"}", 1), "result");
        assertEquals("2025-06-18", result.path("protocolVersion").asString());
        assertEquals("novel-browse", result.path("serverInfo").path("name").asString());
        assertTrue(result.path("capabilities").path("tools").has("listChanged"));
    }

    @Test
    void initializeFallsBackToDefaultVersionWhenUnknown() {
        JsonNode result = readResult(message("initialize", "{\"protocolVersion\":\"1999-01-01\"}", 1), "result");
        assertEquals("2025-06-18", result.path("protocolVersion").asString());
    }

    @Test
    void toolsListExposesTheFourTools() {
        JsonNode tools = readResult(message("tools/list", "{}", 2), "result").path("tools");
        assertEquals(4, tools.size());
        assertTrue(tools.toString().contains("upload_epub"));
        assertTrue(tools.toString().contains("update_novel"));
        assertTrue(tools.toString().contains("list_categories"));
        assertTrue(tools.toString().contains("list_novels"));
    }

    @Test
    void notificationsGetNoResponse() {
        assertEquals(null, server.handle("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"));
    }

    @Test
    void unknownMethodReturnsJsonRpcError() {
        JsonNode error = read(message("no/such/method", "{}", 3)).path("error");
        assertEquals(-32601, error.path("code").asInt());
    }

    @Test
    void malformedBodyReturnsParseError() {
        assertEquals(-32700, read("not-json").path("error").path("code").asInt());
    }

    @Test
    void uploadRejectsNonEpubFileWithoutTouchingTheService() {
        JsonNode result = callTool("upload_epub", "{\"fileName\":\"bad.txt\",\"contentBase64\":\"AA==\"}");
        assertTrue(result.path("isError").asBoolean(), result.toString());
        assertTrue(textOf(result).contains("Only EPUB files (.epub) are supported"), textOf(result));
        verify(adminService, never()).upload(any(byte[].class), anyString(), anyLong(), any(), any());
    }

    @Test
    void uploadRejectsInvalidBase64() {
        JsonNode result = callTool("upload_epub", "{\"fileName\":\"a.epub\",\"contentBase64\":\"not-base64!!\"}");
        assertTrue(result.path("isError").asBoolean());
        assertTrue(textOf(result).contains("base64"), textOf(result));
    }

    @Test
    void uploadReportsUnknownCategoryWithTheAvailableOnes() {
        when(novelService.listCategories()).thenReturn(java.util.List.of(new Category("玄幻奇幻", "", 1)));
        when(categoryRepository.findByName("不存在")).thenReturn(Optional.empty());
        JsonNode result = callTool("upload_epub",
                "{\"fileName\":\"a.epub\",\"contentBase64\":\"AA==\",\"categoryName\":\"不存在\"}");
        assertTrue(textOf(result).contains("Category not found"), textOf(result));
        assertTrue(textOf(result).contains("玄幻奇幻"), textOf(result));
    }

    @Test
    void updateRequiresAnId() {
        JsonNode result = callTool("update_novel", "{\"title\":\"x\"}");
        assertTrue(result.path("isError").asBoolean());
        assertTrue(textOf(result).contains("id is required"), textOf(result));
        verify(adminService, never()).updateNovel(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void unknownToolIsReportedAsToolError() {
        JsonNode result = callTool("no_such_tool", "{}");
        assertTrue(result.path("isError").asBoolean());
        assertTrue(textOf(result).contains("Unknown tool"), textOf(result));
    }

    @Test
    void emptyResourcesAndPromptsAreAnswered() {
        assertTrue(readResult(message("resources/list", "{}", 4), "result").path("resources").isArray());
        assertTrue(readResult(message("prompts/list", "{}", 5), "result").path("prompts").isArray());
        assertTrue(readResult(message("ping", "{}", 6), "result").isObject());
    }

    // ------------------------------------------------------------------

    private String message(String method, String params, int id) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"" + method + "\",\"params\":" + params + "}";
    }

    private JsonNode callTool(String name, String arguments) {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/call\",\"params\":{\"name\":\"" + name
                + "\",\"arguments\":" + arguments + "}}";
        return readResult(body, "result");
    }

    private JsonNode readResult(String body, String field) {
        return read(body).path(field);
    }

    private JsonNode read(String body) {
        try {
            return mapper.readTree(server.handle(body));
        } catch (Exception e) {
            throw new AssertionError("Invalid JSON response: " + server.handle(body), e);
        }
    }

    private String textOf(JsonNode result) {
        return result.path("content").path(0).path("text").asString("");
    }
}
