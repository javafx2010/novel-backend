package com.novel.browse.service.epub;

import com.novel.browse.service.BadRequestException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EpubParserTest {

    private final EpubParser parser = new EpubParser();

    @Test
    void parseMultiFileEpub() {
        byte[] epub = zip(
                entry("META-INF/container.xml", container("OEBPS/content.opf")),
                entry("OEBPS/content.opf", opf("c1", "c2")),
                entry("OEBPS/Text/c1.xhtml", xhtml("第一章 初见", "山门前的少年提着木剑。", "风里有松针的味道。")),
                entry("OEBPS/Text/c2.xhtml", xhtml("第二章 藏经阁", "第三层角落里有一册薄书。", "封面上只有一道剑痕。")));

        EpubBook book = parser.parse(epub, "book.epub");

        assertEquals("剑痕录", book.title());
        assertEquals("佚名", book.author());
        assertEquals(2, book.chapters().size());
        assertEquals("第一章 初见", book.chapters().get(0).title());
        assertTrue(book.chapters().get(0).text().contains("山门前的少年提着木剑。"));
        // 标题不应在正文中重复出现
        assertTrue(!book.chapters().get(0).text().startsWith("第一章"));
        assertTrue(book.wordCount() > 0);
    }

    @Test
    void splitSingleFileByHeadings() {
        StringBuilder body = new StringBuilder();
        for (int i = 1; i <= 3; i++) {
            body.append("<h2>第").append(i).append("节</h2>");
            for (int j = 0; j < 30; j++) {
                body.append("<p>第").append(i).append("节的正文段落：夜色渐深，灯火一盏盏亮起，"
                        + "远处传来更鼓的声音，他握紧手中的信，犹豫了很久才把它放进怀里，转身走入雨中。</p>");
            }
        }
        byte[] epub = zip(
                entry("META-INF/container.xml", container("content.opf")),
                entry("content.opf", opf("only")),
                entry("Text/only.xhtml",
                        "<?xml version=\"1.0\" encoding=\"utf-8\"?><html xmlns=\"http://www.w3.org/1999/xhtml\">"
                                + "<head><title>长夜行</title></head><body><div>" + body + "</div></body></html>"));

        EpubBook book = parser.parse(epub, "single.epub");

        assertEquals(3, book.chapters().size());
        assertEquals("第1节", book.chapters().get(0).title());
        assertEquals("第3节", book.chapters().get(2).title());
    }

    @Test
    void rejectNonEpubZip() {
        byte[] plain = zip(entry("a.txt", "hello"));
        BadRequestException e = assertThrows(BadRequestException.class, () -> parser.parse(plain, "plain.epub"));
        assertTrue(e.getMessage().contains("不是合法的 EPUB"));
    }

    @Test
    void rejectCorruptedZip() {
        BadRequestException e = assertThrows(BadRequestException.class,
                () -> parser.parse("not a zip".getBytes(StandardCharsets.UTF_8), "bad.epub"));
        assertTrue(e.getMessage().contains("zip"), e.getMessage());
    }

    // ------------------------------------------------------------------
    // 测试用 EPUB 构造
    // ------------------------------------------------------------------

    private static String container(String opfPath) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">"
                + "<rootfiles><rootfile full-path=\"" + opfPath + "\" "
                + "media-type=\"application/oebps-package+xml\"/></rootfiles></container>";
    }

    private static String opf(String... ids) {
        StringBuilder manifest = new StringBuilder();
        StringBuilder spine = new StringBuilder();
        for (String id : ids) {
            manifest.append("<item id=\"").append(id).append("\" href=\"Text/").append(id)
                    .append(".xhtml\" media-type=\"application/xhtml+xml\"/>");
            spine.append("<itemref idref=\"").append(id).append("\"/>");
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"2.0\" unique-identifier=\"bookid\">"
                + "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">"
                + "<dc:title>剑痕录</dc:title><dc:creator>佚名</dc:creator><dc:language>zh-CN</dc:language>"
                + "<dc:identifier id=\"bookid\">urn:uuid:test</dc:identifier>"
                + "<dc:subject>玄幻</dc:subject></metadata>"
                + "<manifest>" + manifest + "</manifest><spine>" + spine + "</spine></package>";
    }

    private static String xhtml(String title, String... paragraphs) {
        StringBuilder body = new StringBuilder();
        for (String p : paragraphs) {
            body.append("<p>").append(p).append("</p>");
        }
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><meta charset=\"utf-8\"/><title>"
                + title + "</title></head><body><h1>" + title + "</h1>" + body + "</body></html>";
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
