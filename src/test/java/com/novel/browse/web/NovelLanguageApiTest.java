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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Multi language editions: the {@code language} field of the upload API, the version group of one work and
 * filtering by language.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class NovelLanguageApiTest {

    /** Every test puts its books into its own category so the shared in-memory database stays predictable */
    private static final String HOME = "首页测试";
    private static final String RELATED = "推荐测试";
    private static final String CATEGORIES = "玄幻奇幻";

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
    void homeAggregatesAndAdminListCanBeRestrictedToOneLanguage() {
        // other test classes share the in-memory database, so every count is measured against a baseline
        long totalBefore = get("/home").path("totalNovels").asLong();
        long chineseBefore = get("/home?language=zh_CN").path("totalNovels").asLong();

        upload(epub("星海孤舟", "佚名", "zh-CN", "第一章 启航", "星船驶出港口。"), "star-zh.epub", "zh_CN", null, HOME);
        upload(epub("星海孤舟", "佚名", "en", "Chapter One", "The star ship left the harbour."), "star-en.epub", "en", null, HOME);
        upload(epub("雪夜列车", "青野", "zh-CN", "第一章 发车", "列车在雪夜里离站。"), "snow-zh.epub", "zh_CN", "FINISHED", HOME);

        JsonNode chineseHome = get("/home?language=zh_CN");
        JsonNode englishHome = get("/home?language=en");

        // every block of the home page, the counter included, respects the requested language
        assertEquals(chineseBefore + 2, chineseHome.path("totalNovels").asLong());
        assertEquals(totalBefore + 3, get("/home").path("totalNovels").asLong());
        assertOnlyLanguage(chineseHome.path("recommends"), "zh_CN");
        assertOnlyLanguage(chineseHome.path("hot"), "zh_CN");
        assertOnlyLanguage(chineseHome.path("finished"), "zh_CN");
        assertOnlyLanguage(chineseHome.path("latest"), "novel", "zh_CN");
        assertOnlyLanguage(chineseHome.path("categories"), "zh_CN");
        assertOnlyLanguage(englishHome.path("hot"), "en");
        assertOnlyLanguage(englishHome.path("recommends"), "en");
        assertOnlyLanguage(englishHome.path("categories"), "en");

        // the admin list filters the same way
        assertEquals(chineseBefore + 2, getArray("/admin/novels?language=zh_CN").size());
        assertTrue(getArray("/admin/novels?language=en").size() >= 1);
        assertTrue(getArray("/admin/novels").size() >= getArray("/admin/novels?language=zh_CN").size());
    }

    @Test
    void oneCategoryPerLanguageWithItsOwnDescription() {
        JsonNode chinese = uploadWithCategory(
                epub("孤舟远影", "佚名", "zh-CN", "第一章 远航", "孤舟驶向远海。"), "boat-zh.epub", "zh_CN", CATEGORIES,
                "御剑乘风，问长生何处");
        JsonNode english = uploadWithCategory(
                epub("孤舟远影", "佚名", "en", "Chapter One", "The lonely boat sailed away."), "boat-en.epub", "en",
                CATEGORIES, "Swords and immortals");

        JsonNode zhCategory = category("zh_CN", CATEGORIES);
        JsonNode enCategory = category("en", CATEGORIES);
        // the same name in two languages is two categories, each with the description of its language
        assertNotEquals(zhCategory.path("id").asLong(), enCategory.path("id").asLong());
        assertEquals("御剑乘风，问长生何处", zhCategory.path("description").asString());
        assertEquals("Swords and immortals", enCategory.path("description").asString());
        // and each edition is filed under the category of its own language
        assertEquals(zhCategory.path("id").asLong(), chinese.path("categoryId").asLong());
        assertEquals(enCategory.path("id").asLong(), english.path("categoryId").asLong());
    }

    /** The category of one language as returned by {@code GET /categories} */
    private JsonNode category(String language, String name) {
        for (JsonNode item : getArray("/categories?language=" + language)) {
            if (name.equals(item.path("name").asString())) {
                return item;
            }
        }
        throw new AssertionError("no category " + name + " for " + language);
    }

    private JsonNode uploadWithCategory(byte[] epub, String fileName, String language, String categoryName,
                                        String categoryDescription) {
        return upload(epub, fileName, language, null, categoryName, categoryDescription);
    }

    @Test
    void relatedNovelsSkipTheOtherLanguageVersionsOfTheSameWork() {
        JsonNode chinese = upload(epub("长明灯", "佚名", "zh-CN", "第一章 点火", "灯芯燃了起来。"),
                "lamp-zh.epub", "zh_CN", null, RELATED);
        JsonNode english = upload(epub("长明灯", "佚名", "en", "Chapter One", "The wick caught fire."),
                "lamp-en.epub", "en", null, RELATED);
        upload(epub("山海志", "青野", "zh-CN", "第一章 登山", "行者走进山中。"), "mountain-zh.epub", "zh_CN", null, RELATED);

        JsonNode related = get("/novels/" + chinese.path("id").asLong()).path("related");
        assertEquals(1, related.size(), related.toString());
        // the English edition of the same work is reachable through /versions, not through recommendations
        assertEquals("山海志", related.get(0).path("title").asString());
        assertNotEquals(english.path("id").asLong(), related.get(0).path("id").asLong());
    }

    /** Every element carries the expected language and the list is not empty */
    private void assertOnlyLanguage(JsonNode array, String expected) {
        assertOnlyLanguage(array, null, expected);
    }

    /**
     * Every element carries the expected language (in {@code child} when the element wraps the novel)
     * and the list is not empty.
     */
    private void assertOnlyLanguage(JsonNode array, String child, String expected) {
        assertNotEquals(0, array.size(), "empty list, nothing was filtered: " + array);
        for (JsonNode item : array) {
            JsonNode novel = child == null ? item : item.path(child);
            assertEquals(expected, novel.path("language").asString(), array.toString());
        }
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
        return upload(epub, fileName, language, null, null);
    }

    private JsonNode upload(byte[] epub, String fileName, String language, String status, String categoryName) {
        return upload(epub, fileName, language, status, categoryName, null);
    }

    private JsonNode upload(byte[] epub, String fileName, String language, String status, String categoryName,
                            String categoryDescription) {
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
        if (status != null) {
            body.add("status", status);
        }
        if (categoryName != null) {
            body.add("categoryName", categoryName);
        }
        if (categoryDescription != null) {
            body.add("categoryDescription", categoryDescription);
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
