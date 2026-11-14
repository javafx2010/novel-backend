package com.novel.browse.service.epub;

import com.novel.browse.domain.Chapter;
import com.novel.browse.domain.Novel;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The market generates an EPUB for books that have no stored source file. */
class MarketEpubBuilderTest {

    @Test
    void buildsAValidEpubFromChapters() throws Exception {
        Novel novel = new Novel();
        novel.setId(59L);
        novel.setTitle("新闻 & 日报");
        novel.setAuthor("新闻抓取");
        novel.setLanguage("zh_CN");

        Chapter first = new Chapter();
        first.setChapterNo(1);
        first.setTitle("第一章 <头条>");
        first.setContent("第一段\n第二段 & 更多");

        Chapter second = new Chapter();
        second.setChapterNo(2);
        second.setTitle("第二章");
        second.setContent("内容");

        byte[] epub = new MarketEpubBuilder().build(novel, List.of(first, second));

        Map<String, String> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(epub))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }

        assertEquals("application/epub+zip", entries.get("mimetype"));
        assertTrue(entries.containsKey("META-INF/container.xml"));
        String opf = entries.get("OEBPS/content.opf");
        assertTrue(opf.contains("<dc:title>新闻 &amp; 日报</dc:title>"), opf);
        assertTrue(opf.contains("urn:novel:59"), opf);
        assertTrue(opf.contains("idref=\"c1\"") && opf.contains("idref=\"c2\""), opf);
        assertTrue(entries.get("OEBPS/chap1.xhtml").contains("第二段 &amp; 更多"));
        assertTrue(entries.get("OEBPS/nav.xhtml").contains("第一章 &lt;头条&gt;"));
    }
}
