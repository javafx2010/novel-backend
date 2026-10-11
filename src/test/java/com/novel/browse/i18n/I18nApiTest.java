package com.novel.browse.i18n;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Language of the REST error messages: {@code Accept-Language}, the {@code ?lang=} override and the fallback to
 * {@code app.i18n.default-locale} (Chinese in {@code application.yml}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class I18nApiTest {

    @TempDir
    static Path dir;

    @Value("${local.server.port}")
    private int port;

    private final HttpClient client = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.db.snapshot.file", () -> dir.resolve("db.sql").toString());
        registry.add("app.upload.dir", () -> dir.resolve("epub").toString());
        registry.add("app.cover.dir", () -> dir.resolve("covers").toString());
        registry.add("rocksdb.cache.path", () -> dir.resolve("cache").toString());
        registry.add("app.upload.scan-on-start", () -> "false");
    }

    @Test
    void messagesUseTheDefaultLocaleWithoutAnyLanguageHint() {
        Response response = get("/novels/9999", null);

        assertEquals(404, response.status());
        assertEquals("小说不存在：id=9999", response.message());
        assertEquals("zh-CN", response.language());
    }

    @Test
    void acceptLanguageSwitchesTheMessage() {
        Response response = get("/novels/9999", "zh-CN");

        assertEquals(404, response.status());
        assertEquals("小说不存在：id=9999", response.message());
        assertEquals("zh-CN", response.language());
    }

    @Test
    void langParameterWinsOverAcceptLanguage() {
        assertEquals("小说不存在：id=9999", get("/novels/9999?lang=zh_CN", "en-US").message());
        assertEquals("Novel not found: id=9999", get("/novels/9999?lang=en", "zh-CN").message());
    }

    @Test
    void unsupportedLanguageFallsBackToTheDefaultLocale() {
        Response response = get("/novels/9999", "fr-FR,fr;q=0.9");

        assertEquals("小说不存在：id=9999", response.message());
        assertEquals("zh-CN", response.language());
    }

    private Response get(String path, String language) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .header("Accept", "application/json");
            if (language != null) {
                builder.header("Accept-Language", language);
            }
            HttpResponse<String> response = client.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString());
            JsonNode body = response.body() == null || response.body().isBlank()
                    ? mapper.createObjectNode()
                    : mapper.readTree(response.body());
            return new Response(response.statusCode(), body,
                    response.headers().firstValue("Content-Language").orElse(null));
        } catch (Exception e) {
            throw new IllegalStateException("request failed: " + path, e);
        }
    }

    private record Response(int status, JsonNode body, String language) {

        String message() {
            return body.path("message").asString();
        }
    }
}
