package com.novel.browse.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Cover image storage: extracted EPUB covers are written to {@code app.cover.dir},
 * the bundled demo covers are served from {@code classpath:/static/covers/}.
 * <p>
 * Both are exposed under the same URL prefix, so a novel only needs the file name.
 */
@Component
public class CoverStorage {

    /** URL prefix under which covers are served */
    public static final String URL_PREFIX = "/covers/";

    private static final Logger log = LoggerFactory.getLogger(CoverStorage.class);

    private final Path dir;

    public CoverStorage(@Value("${app.cover.dir:./data/covers}") String dir) {
        this.dir = Path.of(dir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.dir);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create the cover directory: " + this.dir, e);
        }
        log.info("Cover directory: {}", this.dir);
    }

    public Path dir() {
        return dir;
    }

    /**
     * Saves a cover image and returns its file name.
     *
     * @param data      raw image bytes
     * @param mediaType MIME type reported by the EPUB
     * @param hash      content hash of the EPUB, used to build a stable file name
     */
    public String save(byte[] data, String mediaType, String hash) {
        String name = (hash == null || hash.length() < 8 ? "cover" : hash.substring(0, 8).toLowerCase(Locale.ROOT))
                + "-cover." + extension(mediaType);
        try {
            Files.write(dir.resolve(name), data);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to save the cover: " + name, e);
        }
        log.debug("Cover saved: {}", name);
        return name;
    }

    /** Deletes a cover file; a missing file is ignored */
    public void delete(String fileName) {
        if (fileName == null || fileName.isBlank() || fileName.contains("..") || fileName.contains("/")) {
            return;
        }
        try {
            Files.deleteIfExists(dir.resolve(fileName));
        } catch (IOException e) {
            log.warn("Failed to delete the cover file: {} ({})", fileName, e.getMessage());
        }
    }

    /** URL of a cover file name, null when there is no cover */
    public static String url(String fileName) {
        return fileName == null || fileName.isBlank() ? null : URL_PREFIX + fileName;
    }

    private static String extension(String mediaType) {
        if (mediaType == null) {
            return "jpg";
        }
        String type = mediaType.toLowerCase(Locale.ROOT);
        if (type.contains("png")) {
            return "png";
        }
        if (type.contains("gif")) {
            return "gif";
        }
        if (type.contains("webp")) {
            return "webp";
        }
        if (type.contains("svg")) {
            return "svg";
        }
        return "jpg";
    }
}
