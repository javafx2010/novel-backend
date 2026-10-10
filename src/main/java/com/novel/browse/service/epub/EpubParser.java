package com.novel.browse.service.epub;

import com.novel.browse.service.BadRequestException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Lightweight EPUB parser: read the zip, locate the OPF via container.xml, extract content in spine order.
 * <p>
 * Conventions and trade-offs:
 * <ul>
 *     <li>One spine document is one chapter; a single document with several h1/h2 headings is split by heading</li>
 *     <li>Word count is the number of non-whitespace characters, consistent with the demo data</li>
 *     <li>Empty chapters and non-text resources (images, styles, ncx/nav) are skipped</li>
 * </ul>
 */
@Component
public class EpubParser {

    private static final Logger log = LoggerFactory.getLogger(EpubParser.class);

    /** Matches the Chapter.content column length, longer content is truncated */
    private static final int MAX_CHAPTER_LENGTH = 200_000;
    /** A single document is split by heading only when its content exceeds this length and has several headings */
    private static final int SPLIT_THRESHOLD = 3_000;
    /** Line separator: one blank line between paragraphs */
    private static final String PARAGRAPH_BREAK = "\n\n";

    private static final Pattern ENCODING = Pattern.compile("encoding\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern BLOCK_END = Pattern.compile("(?i)</(p|div|li|blockquote|h[1-6]|tr|section)>");
    private static final Pattern BR = Pattern.compile("(?i)<br\\s*/?>");
    private static final Pattern TAG = Pattern.compile("(?i)<[^>]+>");

    /**
     * Parses raw EPUB content.
     *
     * @param data     EPUB file content
     * @param fileName original file name, used for error messages only
     */
    public EpubBook parse(byte[] data, String fileName) {
        Map<String, byte[]> entries = readZip(data, fileName);
        String opfPath = locateOpf(entries, fileName);
        org.w3c.dom.Document opf = parseXml(entries.get(opfPath), opfPath, fileName);
        String base = baseDir(opfPath);

        String title = firstText(opf, "dc:title");
        String author = firstText(opf, "dc:creator");
        String description = firstText(opf, "dc:description");
        String language = firstText(opf, "dc:language");
        List<String> subjects = allText(opf, "dc:subject");

        Map<String, ManifestItem> manifest = readManifest(opf, base);
        List<String> spine = readSpine(opf);

        List<EpubChapter> chapters = new ArrayList<>();
        int skipped = 0;
        for (String idref : spine) {
            ManifestItem item = manifest.get(idref);
            if (item == null) {
                skipped++;
                continue;
            }
            if (!isTextType(item.mediaType())) {
                skipped++;
                continue;
            }
            byte[] raw = findEntry(entries, item.href());
            if (raw == null) {
                log.warn("EPUB {} is missing a resource referenced by the spine: {}", fileName, item.href());
                skipped++;
                continue;
            }
            chapters.addAll(readChapters(raw, item.href()));
        }

        if (chapters.isEmpty()) {
            throw new BadRequestException("No chapter content could be parsed from the EPUB: " + fileName
                    + " (spine items: " + spine.size() + ", skipped: " + skipped + ")");
        }
        log.info("EPUB parsed: {} - title={}, author={}, chapters={}, skipped={}",
                fileName, title, author, chapters.size(), skipped);
        return new EpubBook(title, author, description, language, subjects, chapters);
    }

    // ------------------------------------------------------------------
    // zip
    // ------------------------------------------------------------------

    private Map<String, byte[]> readZip(byte[] data, String fileName) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                entries.putIfAbsent(entry.getName(), zin.readAllBytes());
            }
        } catch (IOException e) {
            throw new BadRequestException("Cannot unzip the EPUB (not a valid zip archive): " + fileName);
        }
        if (entries.isEmpty()) {
            throw new BadRequestException("The EPUB is empty or not a valid zip archive: " + fileName);
        }
        return entries;
    }

    /** Zip entry lookup: exact match first, then a fallback by file name (covers case and URL encoding differences) */
    private byte[] findEntry(Map<String, byte[]> entries, String href) {
        byte[] exact = entries.get(href);
        if (exact != null) {
            return exact;
        }
        String decoded = href.replace("%20", " ");
        exact = entries.get(decoded);
        if (exact != null) {
            return exact;
        }
        String name = decoded.substring(decoded.lastIndexOf('/') + 1);
        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            String key = e.getKey();
            if (key.equals(decoded) || key.endsWith("/" + name)) {
                return e.getValue();
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // OPF
    // ------------------------------------------------------------------

    private String locateOpf(Map<String, byte[]> entries, String fileName) {
        byte[] container = findEntry(entries, "META-INF/container.xml");
        if (container != null) {
            org.w3c.dom.Document doc = parseXml(container, "META-INF/container.xml", fileName);
            NodeList rootfiles = doc.getElementsByTagName("rootfile");
            for (int i = 0; i < rootfiles.getLength(); i++) {
                Node node = rootfiles.item(i);
                String path = attribute(node, "full-path");
                if (path != null && !path.isBlank()) {
                    return path.trim();
                }
            }
        }
        for (String name : entries.keySet()) {
            if (name.toLowerCase(Locale.ROOT).endsWith(".opf")) {
                return name;
            }
        }
        throw new BadRequestException("Not a valid EPUB (missing META-INF/container.xml and .opf): " + fileName);
    }

    private org.w3c.dom.Document parseXml(byte[] data, String name, String fileName) {
        if (data == null) {
            throw new BadRequestException("Missing file inside the EPUB: " + name);
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            trySetFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
            trySetFeature(factory, "http://xml.org/sax/features/external-general-entities", false);
            trySetFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(new ByteArrayInputStream(data));
        } catch (Exception e) {
            throw new BadRequestException("Failed to parse XML inside the EPUB: " + name + " (" + e.getMessage() + ")");
        }
    }

    private void trySetFeature(DocumentBuilderFactory factory, String feature, boolean value) {
        try {
            factory.setFeature(feature, value);
        } catch (Exception ignored) {
            // Some parsers do not support this feature, safe to ignore
        }
    }

    private Map<String, ManifestItem> readManifest(org.w3c.dom.Document opf, String base) {
        Map<String, ManifestItem> items = new LinkedHashMap<>();
        NodeList nodes = opf.getElementsByTagName("item");
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            String id = attribute(node, "id");
            String href = attribute(node, "href");
            if (id == null || href == null) {
                continue;
            }
            String mediaType = attribute(node, "media-type");
            String path = href.contains("/") || base.isEmpty() ? href : base + href;
            items.put(id, new ManifestItem(normalizePath(path), mediaType == null ? "" : mediaType));
        }
        return items;
    }

    private List<String> readSpine(org.w3c.dom.Document opf) {
        List<String> ids = new ArrayList<>();
        NodeList nodes = opf.getElementsByTagName("itemref");
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if ("no".equalsIgnoreCase(attribute(node, "linear"))) {
                continue;
            }
            String idref = attribute(node, "idref");
            if (idref != null && !idref.isBlank()) {
                ids.add(idref);
            }
        }
        return ids;
    }

    private boolean isTextType(String mediaType) {
        if (mediaType == null || mediaType.isBlank()) {
            return false;
        }
        String type = mediaType.toLowerCase(Locale.ROOT);
        return type.equals("application/xhtml+xml") || type.equals("text/html") || type.equals("text/plain");
    }

    private String firstText(org.w3c.dom.Document opf, String tag) {
        NodeList nodes = opf.getElementsByTagName(tag);
        if (nodes.getLength() == 0) {
            return null;
        }
        String text = nodes.item(0).getTextContent();
        return text == null || text.isBlank() ? null : text.trim();
    }

    private List<String> allText(org.w3c.dom.Document opf, String tag) {
        List<String> values = new ArrayList<>();
        NodeList nodes = opf.getElementsByTagName(tag);
        for (int i = 0; i < nodes.getLength(); i++) {
            String text = nodes.item(i).getTextContent();
            if (text != null && !text.isBlank()) {
                values.add(text.trim());
            }
        }
        return values;
    }

    private String attribute(Node node, String name) {
        Node attr = node.getAttributes().getNamedItem(name);
        return attr == null ? null : attr.getNodeValue();
    }

    private String baseDir(String path) {
        int idx = path.lastIndexOf('/');
        return idx < 0 ? "" : path.substring(0, idx + 1);
    }

    /** Removes ./ and ../ segments from a path */
    private String normalizePath(String path) {
        String[] parts = path.split("/");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty() || ".".equals(part)) {
                continue;
            }
            if ("..".equals(part)) {
                int idx = sb.lastIndexOf("/");
                if (idx >= 0) {
                    sb.delete(idx, sb.length());
                }
                continue;
            }
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(part);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // content
    // ------------------------------------------------------------------

    private List<EpubChapter> readChapters(byte[] raw, String href) {
        String charset = detectCharset(raw);
        Document doc;
        try {
            doc = Jsoup.parse(new ByteArrayInputStream(raw), charset, "");
        } catch (IOException e) {
            throw new BadRequestException("Failed to read chapter: " + href);
        }
        String docTitle = docTitle(doc, href);
        Element container = contentRoot(doc);
        dropLeadingTitle(container, docTitle);
        Elements headings = container.select("h1,h2");
        if (headings.size() >= 2 && container.text().length() > SPLIT_THRESHOLD) {
            return splitByHeadings(container, docTitle, href);
        }
        String text = extractText(container);
        if (text.isEmpty()) {
            return List.of();
        }
        return List.of(new EpubChapter(docTitle, truncate(text)));
    }

    /** Removes a leading heading that duplicates the chapter title, so the title is not repeated in the content */
    private void dropLeadingTitle(Element container, String docTitle) {
        Element first = container.firstElementChild();
        if (first == null || docTitle == null) {
            return;
        }
        String tag = first.tagName();
        boolean heading = "h1".equals(tag) || "h2".equals(tag) || "h3".equals(tag);
        if (heading && first.text().trim().equals(docTitle.trim())) {
            first.remove();
        }
    }

    /** Content container: body, descends one level when body wraps a single div */
    private Element contentRoot(Document doc) {
        Element body = doc.body();
        if (body == null) {
            body = doc;
        }
        Element current = body;
        while (current.children().size() == 1 && "div".equals(current.child(0).tagName())) {
            current = current.child(0);
        }
        return current;
    }

    private List<EpubChapter> splitByHeadings(Element container, String docTitle, String href) {
        List<EpubChapter> chapters = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String currentTitle = docTitle;
        boolean started = false;
        for (Element child : container.children()) {
            if ("h1".equals(child.tagName()) || "h2".equals(child.tagName())) {
                String heading = child.text().trim();
                if (started) {
                    addChapter(chapters, currentTitle, current);
                    current.setLength(0);
                }
                currentTitle = heading.isEmpty() ? docTitle : heading;
                started = true;
                continue;
            }
            appendBlock(current, child);
            started = true;
        }
        addChapter(chapters, currentTitle, current);
        if (chapters.isEmpty()) {
            String text = extractText(container);
            return text.isEmpty() ? List.of() : List.of(new EpubChapter(docTitle, truncate(text)));
        }
        return chapters;
    }

    private void addChapter(List<EpubChapter> chapters, String title, StringBuilder buffer) {
        String text = TextUtil.normalize(buffer.toString());
        if (text.isEmpty()) {
            return;
        }
        chapters.add(new EpubChapter(title, truncate(text)));
    }

    private void appendBlock(StringBuilder buffer, Element element) {
        String html = element.html();
        html = BR.matcher(html).replaceAll("\n");
        html = BLOCK_END.matcher(html).replaceAll(PARAGRAPH_BREAK);
        String text = Parser.unescapeEntities(TAG.matcher(html).replaceAll(""), false);
        text = text.replace('\u00a0', ' ').trim();
        if (!text.isEmpty()) {
            buffer.append(text).append(PARAGRAPH_BREAK);
        }
    }

    private String extractText(Element container) {
        String html = container.html();
        html = BR.matcher(html).replaceAll("\n");
        html = BLOCK_END.matcher(html).replaceAll(PARAGRAPH_BREAK);
        String text = Parser.unescapeEntities(TAG.matcher(html).replaceAll(""), false);
        return TextUtil.normalize(text);
    }

    private String truncate(String text) {
        if (text.length() <= MAX_CHAPTER_LENGTH) {
            return text;
        }
        log.warn("Chapter content exceeds {} characters, truncated", MAX_CHAPTER_LENGTH);
        return TextUtil.normalize(text.substring(0, MAX_CHAPTER_LENGTH));
    }

    private String docTitle(Document doc, String href) {
        Element title = doc.selectFirst("title");
        if (title != null && !title.text().isBlank()) {
            return title.text().trim();
        }
        Element heading = doc.selectFirst("h1,h2,h3");
        if (heading != null && !heading.text().isBlank()) {
            return heading.text().trim();
        }
        String name = href.substring(href.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private String detectCharset(byte[] raw) {
        String head = new String(raw, 0, Math.min(raw.length, 1024), StandardCharsets.US_ASCII);
        Matcher matcher = ENCODING.matcher(head);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return StandardCharsets.UTF_8.name();
    }

    private record ManifestItem(String href, String mediaType) {
    }
}
