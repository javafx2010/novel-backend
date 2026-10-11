package com.novel.browse.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Multi language editions: the {@code language} field of the upload API, the version group of one work and
 * filtering by language.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class NovelLanguageApiTest {

    @TempDir
    static Path dir;

    @Value("${local.server.port}")
    private int port;

    private final RestTemplate rest = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.db.snapshot.file", () -> dir.resolve("db.sql").toString());
        registry.add("app.upload.dir", () -> dir.resolve("epub").toString());
        registry.add("app.cover.dir", () -> dir.resolve("covers").toString());
        registry.add("rocksdb.cache.path", () -> dir.resolve("cache").toString());
        registry.add("app.upload.scan-on-start", () -> "false");
    }

    @Test
    void oneWorkCanBeImportedInSeveralLanguages() {
        JsonNode chinese = upload(epub("剑痕录", "佚名", "zh-CN", "第一章 初见", "山门前的少年提着木剑。"),
                "sword-zh.epub", "zh_CN");
        JsonNode english = upload(epub("剑痕录", "佚名", "en", "Chapter One", "The boy carried a wooden sword."),
                "sword-en.epub", "en");

        assertEquals("zh_CN", chinese.path("language").asString());
        assertEquals("en", english.path("language").asString());
        // another language is a separate edition, not an overwrite of the first one
        assertNotEquals(chinese.path("id").asLong(), english.path("id").asLong());

        JsonNode versions = getArray("/novels/" + chinese.path("id").asLong() + "/versions");
        assertEquals(2, versions.size());
        assertEquals("en", versions.get(0).path("language").asString());
        assertEquals("zh_CN", versions.get(1).path("language").asString());

        // the language travels with every listing and with the detail view
        assertEquals("zh_CN", get("/novels/" + chinese.path("id").asLong()).path("novel").path("language").asString());
        assertEquals(1, search("剑痕录", "en").path("totalElements").asLong());
        assertEquals(1, search("剑痕录", "zh_CN").path("totalElements").asLong());
    }

    @Test
    void uploadWithoutLanguageFallsBackToTheEpubMetadata() {
        JsonNode novel = upload(epub("雾中列车", "青野", "en", "Chapter One", "The train left the station at dawn."),
                "train.epub", null);

        assertEquals("en", novel.path("language").asString());
        JsonNode versions = getArray("/novels/" + novel.path("id").asLong() + "/versions");
        assertEquals(1, versions.size());
    }

    private JsonNode search(String keyword, String language) {
        return get("/search?q=" + keyword + "&language=" + language);
    }

    private JsonNode upload(byte[] epub, String fileName, String language) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(epub) {
            @Override
            public String getFilename() {
                return fileName;
            }
        });
        if (language != null) {
            body.add("language", language);
        }
        ResponseEntity<String> response = rest.postForEntity(url("/admin/novels"), new HttpEntity<>(body),
                String.class);
        assertEquals(201, response.getStatusCode().value(), response.getBody());
        return parse(response.getBody());
    }

    private JsonNode get(String path) {
        ResponseEntity<String> response = rest.getForEntity(url(path), String.class);
        assertEquals(200, response.getStatusCode().value(), response.getBody());
        return parse(response.getBody());
    }

    private JsonNode getArray(String path) {
        return get(path);
    }

    private JsonNode parse(String body) {
        try {
            return mapper.readTree(body == null ? "{}" : body);
        } catch (Exception e) {
            throw new IllegalStateException("not JSON: " + body, e);
        }
    }

    // ------------------------------------------------------------------
    // minimal EPUB archives
    // ------------------------------------------------------------------

    private static byte[] epub(String title, String author, String language, String chapterTitle, String text) {
        String opf = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"2.0\" unique-identifier=\"bookid\">"
                + "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">"
                + "<dc:title>" + title + "</dc:title><dc:creator>" + author + "</dc:creator>"
                + "<dc:language>" + language + "</dc:language>"
                + "<dc:identifier id=\"bookid\">urn:uuid:" + Math.abs(title.hashCode()) + "</dc:identifier>"
                + "</metadata><manifest><item id=\"c1\" href=\"Text/c1.xhtml\" "
                + "media-type=\"application/xhtml+xml\"/></manifest><spine><itemref idref=\"c1\"/></spine></package>";
        String xhtml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>" + chapterTitle + "</title></head>"
                + "<body><h1>" + chapterTitle + "</h1><p>" + text + "</p></body></html>";
        return zip(entry("META-INF/container.xml",
                        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                                + "<container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">"
                                + "<rootfiles><rootfile full-path=\"OEBPS/content.opf\" "
                                + "media-type=\"application/oebps-package+xml\"/></rootfiles></container>"),
                entry("OEBPS/content.opf", opf),
                entry("OEBPS/Text/c1.xhtml", xhtml));
    }

    private record Entry(String name, String content) {
    }

    private static Entry entry(String name, String content) {
        return new Entry(name, content);
    }

    private static byte[] zip(Entry... entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            for (Entry e : entries) {
                zos.putNextEntry(new ZipEntry(e.name()));
                zos.write(e.content().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }
}
