package com.novel.browse.web.mcp;

import com.novel.browse.config.McpProperties;
import com.novel.browse.domain.Category;
import com.novel.browse.domain.Novel;
import com.novel.browse.i18n.I18nSettings;
import com.novel.browse.i18n.Messages;
import com.novel.browse.service.AdminService;
import com.novel.browse.service.NovelService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** JSON-RPC dispatch and tool argument checks of the MCP server (no Spring context, no HTTP) */
class McpServerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private AdminService adminService;
    private NovelService novelService;
    private McpServer server;

    /** Real bundles, English locale: the same messages the server returns for a request without Accept-Language */
    private static Messages englishMessages() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("i18n/messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        return new Messages(source, new I18nSettings(Locale.ENGLISH, List.of(Locale.ENGLISH, Locale.SIMPLIFIED_CHINESE)));
    }

    @BeforeEach
    void setUp() {
        // The server resolves messages via LocaleContextHolder, which falls back to the system
        // locale outside a request; pin it to English so assertions hold on any machine
        LocaleContextHolder.setDefaultLocale(Locale.ENGLISH);
        adminService = mock(AdminService.class);
        novelService = mock(NovelService.class);
        server = new McpServer(adminService, novelService, new McpProperties(), englishMessages());
    }

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
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
    void toolsListExposesEveryTool() {
        JsonNode tools = readResult(message("tools/list", "{}", 2), "result").path("tools");
        assertEquals(6, tools.size());
        assertTrue(tools.toString().contains("upload_epub"));
        assertTrue(tools.toString().contains("update_novel"));
        assertTrue(tools.toString().contains("delete_novel"));
        assertTrue(tools.toString().contains("list_categories"));
        assertTrue(tools.toString().contains("list_novels"));
        assertTrue(tools.toString().contains("list_novel_versions"));
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
        verify(adminService, never()).upload(any(byte[].class), anyString(), any(), any(), any(), any(),
                any(), any(), any());
    }

    @Test
    void uploadRejectsInvalidBase64() {
        JsonNode result = callTool("upload_epub", "{\"fileName\":\"a.epub\",\"contentBase64\":\"not-base64!!\"}");
        assertTrue(result.path("isError").asBoolean());
        assertTrue(textOf(result).contains("base64"), textOf(result));
    }

    @Test
    void uploadDelegatesToAdminServiceAndReturnsTheCreatedNovel() {
        Novel novel = new Novel();
        novel.setTitle("新书");
        when(adminService.upload(any(byte[].class), eq("a.epub"), isNull(), eq("玄幻奇幻"), any(), any(),
                any(), any(), any()))
                .thenReturn(novel);
        JsonNode result = callTool("upload_epub",
                "{\"fileName\":\"a.epub\",\"contentBase64\":\"AA==\",\"categoryName\":\"玄幻奇幻\"}");
        assertFalse(result.path("isError").asBoolean(), textOf(result));
        assertEquals("新书", result.path("structuredContent").path("title").asString());
    }

    @Test
    void uploadPassesLanguageAndVersionGroupThrough() {
        Novel novel = edition("剑痕录", "zh_CN", "group-1");
        when(adminService.upload(any(byte[].class), eq("a.epub"), isNull(), isNull(), isNull(), isNull(), isNull(),
                eq("zh_CN"), eq("group-1")))
                .thenReturn(novel);
        JsonNode result = callTool("upload_epub",
                "{\"fileName\":\"a.epub\",\"contentBase64\":\"AA==\",\"language\":\"zh_CN\","
                        + "\"versionGroup\":\"group-1\"}");
        assertFalse(result.path("isError").asBoolean(), textOf(result));
        assertEquals("zh_CN", result.path("structuredContent").path("language").asString());
        // the group travels with the result, so another translation can be linked to this work
        assertEquals("group-1", result.path("structuredContent").path("versionGroup").asString());
    }

    @Test
    void uploadPassesTheCategoryDescriptionThrough() {
        Novel novel = edition("剑痕录", "zh_CN", "group-1");
        when(adminService.upload(any(byte[].class), eq("a.epub"), isNull(), eq("玄幻奇幻"),
                eq("御剑乘风，问长生何处"), isNull(), isNull(), eq("zh_CN"), isNull()))
                .thenReturn(novel);
        JsonNode result = callTool("upload_epub",
                "{\"fileName\":\"a.epub\",\"contentBase64\":\"AA==\",\"categoryName\":\"玄幻奇幻\","
                        + "\"categoryDescription\":\"御剑乘风，问长生何处\",\"language\":\"zh_CN\"}");
        assertFalse(result.path("isError").asBoolean(), textOf(result));
        verify(adminService).upload(any(byte[].class), eq("a.epub"), isNull(), eq("玄幻奇幻"),
                eq("御剑乘风，问长生何处"), isNull(), isNull(), eq("zh_CN"), isNull());
    }

    @Test
    void listCategoriesCanBeRestrictedToOneLanguage() {
        Category chinese = new Category("玄幻奇幻", "御剑乘风", 1, "zh_CN");
        chinese.setId(3L);
        when(novelService.listCategories("zh_CN")).thenReturn(List.of(chinese));

        JsonNode categories = callTool("list_categories", "{\"language\":\"zh_CN\"}")
                .path("structuredContent").path("categories");
        assertEquals(1, categories.size());
        assertEquals("zh_CN", categories.get(0).path("language").asString());
        assertEquals("御剑乘风", categories.get(0).path("description").asString());
        verify(novelService).listCategories("zh_CN");
    }

    @Test
    void listNovelsCanBeRestrictedToOneLanguage() {
        when(adminService.listNovels("zh_CN")).thenReturn(List.of(edition("剑痕录", "zh_CN", "group-1")));

        JsonNode result = callTool("list_novels", "{\"language\":\"zh_CN\"}");
        JsonNode novels = result.path("structuredContent").path("novels");
        assertEquals(1, novels.size());
        assertEquals("zh_CN", novels.get(0).path("language").asString());
        verify(adminService).listNovels("zh_CN");
    }

    @Test
    void listNovelVersionsReturnsEveryLanguageOfAWork() {
        when(novelService.versions(7L)).thenReturn(List.of(edition("剑痕录", "en", "group-1"),
                edition("剑痕录", "zh_CN", "group-1")));

        JsonNode versions = callTool("list_novel_versions", "{\"id\":7}").path("structuredContent").path("versions");
        assertEquals(2, versions.size());
        assertEquals("en", versions.get(0).path("language").asString());
        assertEquals("zh_CN", versions.get(1).path("language").asString());
        assertEquals("group-1", versions.get(0).path("versionGroup").asString());
    }

    @Test
    void listNovelVersionsRequiresAnId() {
        JsonNode result = callTool("list_novel_versions", "{}");
        assertTrue(result.path("isError").asBoolean());
        verify(novelService, never()).versions(any(Long.class));
    }

    @Test
    void updatePassesTheLanguageThrough() {
        when(adminService.updateNovel(eq(7L), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
                eq("en")))
                .thenReturn(edition("剑痕录", "en", "group-1"));

        JsonNode result = callTool("update_novel", "{\"id\":7,\"language\":\"en\"}");
        assertFalse(result.path("isError").asBoolean(), textOf(result));
        assertEquals("en", result.path("structuredContent").path("language").asString());
    }

    @Test
    void updateRequiresAnId() {
        JsonNode result = callTool("update_novel", "{\"title\":\"x\"}");
        assertTrue(result.path("isError").asBoolean());
        assertTrue(textOf(result).contains("id is required"), textOf(result));
        verify(adminService, never()).updateNovel(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void deleteNovelCallsAdminService() {
        Novel novel = edition("剑痕录", "zh_CN", "group-1");
        novel.setId(7L);
        when(novelService.detail(7L)).thenReturn(novel);

        JsonNode result = callTool("delete_novel", "{\"id\":7}").path("structuredContent");
        assertEquals(1, result.path("deleted").asInt());
        assertEquals(7L, result.path("ids").get(0).asLong());
        verify(adminService).deleteNovel(7L);
    }

    @Test
    void deleteNovelCanRemoveTheEntireWork() {
        Novel zh = edition("剑痕录", "zh_CN", "group-1");
        zh.setId(7L);
        Novel en = edition("剑痕录", "en", "group-1");
        en.setId(8L);
        when(novelService.versions(7L)).thenReturn(List.of(zh, en));

        JsonNode result = callTool("delete_novel", "{\"id\":7,\"deleteAllVersions\":true}").path("structuredContent");
        assertEquals(2, result.path("deleted").asInt());
        verify(adminService).deleteNovel(7L);
        verify(adminService).deleteNovel(8L);
    }

    @Test
    void deleteNovelRequiresAnId() {
        JsonNode result = callTool("delete_novel", "{}");
        assertTrue(result.path("isError").asBoolean());
        verify(adminService, never()).deleteNovel(any(Long.class));
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

    private static Novel edition(String title, String language, String versionGroup) {
        Novel novel = new Novel();
        novel.setTitle(title);
        novel.setLanguage(language);
        novel.setVersionGroup(versionGroup);
        return novel;
    }
}
