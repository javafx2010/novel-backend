package com.novel.browse.service.epub;

import com.novel.browse.domain.Chapter;
import com.novel.browse.domain.Novel;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds a minimal but valid EPUB 3 archive on the fly for books that have no stored EPUB source
 * file (for example news imported chapter-by-chapter). The market serves this generated file exactly
 * like an uploaded EPUB: {@code mimetype} + {@code META-INF/container.xml} + an OPF package with one
 * XHTML document per chapter, plus a navigation document.
 */
@Component
public class MarketEpubBuilder {

    private static final String MIMETYPE = "application/epub+zip";

    public byte[] build(Novel novel, List<Chapter> chapters) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            writeMimetype(zip);
            write(zip, "META-INF/container.xml", container());
            write(zip, "OEBPS/content.opf", opf(novel, chapters));
            write(zip, "OEBPS/nav.xhtml", nav(novel, chapters));
            for (int i = 0; i < chapters.size(); i++) {
                write(zip, "OEBPS/chap" + (i + 1) + ".xhtml", chapterXhtml(chapters.get(i)));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to build an EPUB for novel " + novel.getId(), e);
        }
        return out.toByteArray();
    }

    /** The mimetype entry must be the first one and stored uncompressed, per the EPUB spec. */
    private static void writeMimetype(ZipOutputStream zip) throws IOException {
        byte[] bytes = MIMETYPE.getBytes(StandardCharsets.US_ASCII);
        CRC32 crc = new CRC32();
        crc.update(bytes);
        ZipEntry entry = new ZipEntry("mimetype");
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(bytes.length);
        entry.setCompressedSize(bytes.length);
        entry.setCrc(crc.getValue());
        zip.putNextEntry(entry);
        zip.write(bytes);
        zip.closeEntry();
    }

    private static void write(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String container() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">\n"
                + "  <rootfiles>\n"
                + "    <rootfile full-path=\"OEBPS/content.opf\" media-type=\"application/oebps-package+xml\"/>\n"
                + "  </rootfiles>\n"
                + "</container>\n";
    }

    private static String opf(Novel novel, List<Chapter> chapters) {
        String title = escape(text(novel.getTitle(), "Untitled"));
        String author = escape(text(novel.getAuthor(), "Unknown"));
        String language = escape(text(novel.getLanguage(), "zh").replace('_', '-'));

        StringBuilder manifest = new StringBuilder();
        manifest.append("    <item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>\n");
        StringBuilder spine = new StringBuilder();
        for (int i = 0; i < chapters.size(); i++) {
            String id = "c" + (i + 1);
            manifest.append("    <item id=\"").append(id).append("\" href=\"chap").append(i + 1)
                    .append(".xhtml\" media-type=\"application/xhtml+xml\"/>\n");
            spine.append("    <itemref idref=\"").append(id).append("\"/>\n");
        }

        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\" unique-identifier=\"bookid\">\n"
                + "  <metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">\n"
                + "    <dc:identifier id=\"bookid\">urn:novel:" + novel.getId() + "</dc:identifier>\n"
                + "    <dc:title>" + title + "</dc:title>\n"
                + "    <dc:creator>" + author + "</dc:creator>\n"
                + "    <dc:language>" + language + "</dc:language>\n"
                + "    <meta property=\"dcterms:modified\">"
                + Instant.now().truncatedTo(ChronoUnit.SECONDS) + "</meta>\n"
                + "  </metadata>\n"
                + "  <manifest>\n" + manifest + "  </manifest>\n"
                + "  <spine>\n" + spine + "  </spine>\n"
                + "</package>\n";
    }

    private static String nav(Novel novel, List<Chapter> chapters) {
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < chapters.size(); i++) {
            items.append("      <li><a href=\"chap").append(i + 1).append(".xhtml\">")
                    .append(escape(text(chapters.get(i).getTitle(), "第 " + (i + 1) + " 章")))
                    .append("</a></li>\n");
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\">\n"
                + "  <head><title>" + escape(text(novel.getTitle(), "目录")) + "</title></head>\n"
                + "  <body>\n"
                + "    <nav epub:type=\"toc\" id=\"toc\"><h1>目录</h1><ol>\n"
                + items
                + "    </ol></nav>\n"
                + "  </body>\n"
                + "</html>\n";
    }

    private static String chapterXhtml(Chapter chapter) {
        String title = escape(text(chapter.getTitle(), "第 " + chapter.getChapterNo() + " 章"));
        StringBuilder body = new StringBuilder();
        String content = chapter.getContent() == null ? "" : chapter.getContent();
        for (String line : content.split("\\r?\\n")) {
            String paragraph = xmlChars(line).trim();
            if (!paragraph.isEmpty()) {
                body.append("<p>").append(escape(paragraph)).append("</p>\n");
            }
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<html xmlns=\"http://www.w3.org/1999/xhtml\">\n"
                + "  <head><title>" + title + "</title></head>\n"
                + "  <body><h1>" + title + "</h1>\n" + body + "  </body>\n"
                + "</html>\n";
    }

    private static String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    /** Drops characters that are not allowed in an XML 1.0 document. */
    private static String xmlChars(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\t' || c == '\n' || c == '\r' || c >= 0x20) {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static String escape(String value) {
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&apos;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
