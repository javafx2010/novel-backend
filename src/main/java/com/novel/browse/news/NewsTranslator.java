package com.novel.browse.news;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Translates article text with the keyless Google Translate web endpoint
 * ({@code translate.googleapis.com/translate_a/single?client=gtx}).
 *
 * <p>Long texts are split into chunks below the GET URL limit; every chunk is one request with a
 * pause in between. Callers fall back to the original text when the service is unreachable.
 */
@Component
public class NewsTranslator {

    private static final Logger log = LoggerFactory.getLogger(NewsTranslator.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final NewsProperties properties;
    private final HttpClient http;

    public NewsTranslator(NewsProperties properties) {
        this.properties = properties;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                .build();
    }

    /**
     * Translates a whole article (paragraphs separated by {@code \n}); returns the input unchanged
     * when translation is disabled, and throws when the service fails so the caller can fall back.
     */
    public String translate(String text) throws Exception {
        if (text == null || text.isBlank()) {
            return text;
        }
        if (!properties.isTranslateEnabled()) {
            return text;
        }
        List<String> paragraphs = List.of(text.split("\n", -1));
        List<String> chunks = chunk(paragraphs, properties.getTranslateChunkChars());
        List<String> translated = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            translated.add(translateChunk(chunks.get(i)));
            if (i < chunks.size() - 1 && properties.getTranslateDelayMs() > 0) {
                Thread.sleep(properties.getTranslateDelayMs());
            }
        }
        return String.join("\n", translated);
    }

    /** Sends one chunk to the translation endpoint and joins the returned segments */
    private String translateChunk(String chunk) throws Exception {
        String uri = properties.getTranslateEndpoint()
                + "&sl=" + encode(properties.getTranslateFrom())
                + "&tl=" + encode(properties.getTranslateTo())
                + "&q=" + encode(chunk);
        HttpRequest request = HttpRequest.newBuilder(URI.create(uri))
                .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                .header("User-Agent", properties.getUserAgent())
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Translate endpoint answered " + response.statusCode());
        }
        JsonNode root = MAPPER.readTree(response.body());
        JsonNode segments = root.path(0);
        if (!segments.isArray() || segments.isEmpty()) {
            throw new IllegalStateException("Unexpected translate response shape");
        }
        StringBuilder out = new StringBuilder();
        for (JsonNode segment : segments) {
            if (segment.isArray() && !segment.isEmpty()) {
                out.append(segment.get(0).asString());
            }
        }
        return out.toString();
    }

    /** Groups paragraphs into chunks below {@code maxChars}, never splitting a paragraph */
    private static List<String> chunk(List<String> paragraphs, int maxChars) {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String paragraph : paragraphs) {
            String piece = paragraph.isBlank() ? "" : paragraph;
            if (current.length() > 0 && current.length() + piece.length() + 1 > maxChars) {
                chunks.add(current.toString());
                current = new StringBuilder();
            }
            if (piece.length() > maxChars) {
                // A single oversized paragraph: hard split, translation quality suffers slightly
                for (int start = 0; start < piece.length(); start += maxChars) {
                    chunks.add(piece.substring(start, Math.min(start + maxChars, piece.length())));
                }
                continue;
            }
            if (current.length() > 0) {
                current.append('\n');
            }
            current.append(piece);
        }
        if (current.length() > 0) {
            chunks.add(current.toString());
        }
        return chunks;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
