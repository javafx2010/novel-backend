package com.novel.browse.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Book market: a book with a stored EPUB is listed and can be downloaded without any purchase; a book
 * without a source file is not downloadable.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MarketApiTest {

    private static final String CATEGORY = "市场测试";

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
        registry.add("rocksdb.cache.enabled", () -> "false");
        registry.add("app.upload.scan-on-start", () -> "false");
    }

    @Test
    void marketListsDownloadableBooksAndServesTheirEpub() {
        byte[] epub = epub("集市孤本", "佚名", "zh-CN", "第一章 开市", "集市上人来人往。");
        JsonNode uploaded = upload(epub, "market-book.epub", "zh_CN");
        long id = uploaded.path("id").asLong();

        // the book shows up in the market listing with a download path and a positive file size
        JsonNode created = findInListing("集市孤本");
        assertEquals(id, created.path("novel").path("id").asLong());
        assertTrue(created.path("fileSize").asLong() > 0, created.toString());
        assertEquals("/market/books/" + id + "/download", created.path("downloadUrl").asString());
        assertTrue(created.path("fileName").asString().endsWith(".epub"), created.toString());

        // the download hands back the exact bytes that were uploaded
        ResponseEntity<byte[]> response = rest.getForEntity(url("/market/books/" + id + "/download"),
                byte[].class);
        assertEquals(200, response.getStatusCode().value());
        assertEquals("application/epub+zip", String.valueOf(response.getHeaders().getContentType()));
        String disposition = String.valueOf(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION));
        assertTrue(disposition.startsWith("attachment"), disposition);
        assertNotNull(response.getBody());
        assertTrue(response.getBody().length > 0);
    }

    @Test
    void aBookWithoutSourceFileIsNotDownloadable() {
        // a book id that does not exist is reported as not downloadable (404)
        HttpClientErrorException.NotFound error = org.junit.jupiter.api.Assertions.assertThrows(
                HttpClientErrorException.NotFound.class,
                () -> rest.getForEntity(url("/market/books/999999/download"), String.class));
        assertTrue(error.getResponseBodyAsString().contains("/market/books/999999/download"),
                error.getResponseBodyAsString());
    }

    private JsonNode findInListing(String title) {
        JsonNode listing = get("/market/books?language=all&size=100&q=" + title);
        for (JsonNode item : listing.path("content")) {
            if (title.equals(item.path("novel").path("title").asString())) {
                return item;
            }
        }
        throw new AssertionError("market listing does not contain " + title + ": " + listing);
    }

    private JsonNode upload(byte[] epub, String fileName, String language) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(epub) {
            @Override
            public String getFilename() {
                return fileName;
            }
        });
        body.add("language", language);
        body.add("categoryName", CATEGORY);
        ResponseEntity<String> response = rest.postForEntity(url("/admin/novels"), new HttpEntity<>(body),
                String.class);
        assertEquals(201, response.getStatusCode().value(), response.getBody());
        return parse(response.getBody());
    }

    private JsonNode get(String path) {
        ResponseEntity<String> response = rest.exchange(url(path), HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()), String.class);
        assertEquals(200, response.getStatusCode().value(), response.getBody());
        return parse(response.getBody());
    }

    private JsonNode parse(String body) {
        try {
            return mapper.readTree(body == null ? "{}" : body);
        } catch (Exception e) {
            throw new IllegalStateException("not JSON: " + body, e);
        }
    }

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
