package com.novel.browse.service.epub;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * EPUB source file storage: the directory structure maps one-to-one to novel categories.
 * <pre>
 *   data/epub/
 *   ├── Fantasy/
 *   │   └── 6e6e83a8-book.epub
 *   └── Urban Life/
 * </pre>
 * File names look like {@code <hash8>-<safe original name>.epub}; identical content always yields the same name (idempotent).
 */
@Component
public class EpubStorage {

    private static final Logger log = LoggerFactory.getLogger(EpubStorage.class);

    /** Scan result: a null categoryName means the file sits directly in the upload directory root (default category) */
    public record ScannedFile(String categoryName, Path path) {
    }

    private final Path dir;

    public EpubStorage(@Value("${app.upload.dir:./data/epub}") String dir) {
        this.dir = Paths.get(dir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.dir);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create the EPUB upload directory: " + this.dir, e);
        }
        log.info("EPUB upload directory: {}", this.dir);
    }

    public Path dir() {
        return dir;
    }

    /** Subdirectory of a category (returns the path only, does not create it) */
    public Path categoryDir(String categoryName) {
        return dir.resolve(safeDirName(categoryName));
    }

    /**
     * Saves into the category subdirectory (created when missing) and returns the relative path {@code category/file}.
     */
    public String save(byte[] data, String originalName, String categoryName) {
        String dirName = safeDirName(categoryName);
        Path target = dir.resolve(dirName);
        try {
            Files.createDirectories(target);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create the category directory: " + target, e);
        }
        String fileName = fileName(data, originalName);
        Path file = target.resolve(fileName);
        try {
            Files.write(file, data);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to save the EPUB: " + file, e);
        }
        log.debug("EPUB stored: {}", file);
        return dirName + "/" + fileName;
    }

    /** Deletes the file at a relative path, ignores a missing file */
    public void delete(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return;
        }
        Path target = dir.resolve(relativePath).normalize();
        if (!target.startsWith(dir)) {
            log.warn("Refusing to delete a file outside the upload directory: {}", relativePath);
            return;
        }
        try {
            Files.deleteIfExists(target);
            log.debug("EPUB file deleted: {}", target);
        } catch (IOException e) {
            log.warn("Failed to delete the EPUB file: {} ({})", target, e.getMessage());
        }
    }

    /**
     * Resolves a stored relative path to the file on disk, guarding against path traversal.
     *
     * @return the normalized path, or {@code null} when the argument is blank or points outside the
     *         upload directory (the caller still has to check that the file exists)
     */
    public Path resolve(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return null;
        }
        Path target = dir.resolve(relativePath).normalize();
        if (!target.startsWith(dir)) {
            log.warn("Refusing to resolve a path outside the upload directory: {}", relativePath);
            return null;
        }
        return target;
    }

    /** Path of a file relative to the upload directory, stored in the database */
    public String relative(Path file) {
        Path target = file.toAbsolutePath().normalize();
        return target.startsWith(dir) ? dir.relativize(target).toString() : target.getFileName().toString();
    }

    /**
     * Scans the upload directory: a subdirectory name is the category name and its .epub files belong to that category;
     * .epub files placed in the root belong to the default category (categoryName is null).
     */
    public List<ScannedFile> scan() {
        List<ScannedFile> result = new ArrayList<>();
        try (Stream<Path> entries = Files.list(dir)) {
            List<Path> sorted = entries.sorted(Comparator.comparing(Path::getFileName)).toList();
            for (Path entry : sorted) {
                if (Files.isDirectory(entry)) {
                    String categoryName = entry.getFileName().toString();
                    try (Stream<Path> files = Files.list(entry)) {
                        files.filter(Files::isRegularFile)
                                .filter(EpubStorage::isEpub)
                                .sorted(Comparator.comparing(Path::getFileName))
                                .forEach(f -> result.add(new ScannedFile(categoryName, f)));
                    } catch (IOException e) {
                        log.warn("Failed to scan the category directory: {} ({})", entry, e.getMessage());
                    }
                } else if (isEpub(entry)) {
                    result.add(new ScannedFile(null, entry));
                }
            }
        } catch (IOException e) {
            log.warn("Failed to scan the EPUB directory: {} ({})", dir, e.getMessage());
        }
        return result;
    }

    /** Creates a category directory (idempotent), returns whether it was created now */
    public boolean ensureCategoryDir(String categoryName) {
        Path target = categoryDir(categoryName);
        if (Files.isDirectory(target)) {
            return false;
        }
        try {
            Files.createDirectories(target);
            return true;
        } catch (IOException e) {
            log.warn("Failed to create the category directory: {} ({})", target, e.getMessage());
            return false;
        }
    }

    /** SHA-256 of the file content (lowercase hex) */
    public String sha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /** File name: first 8 hash characters plus a sanitized original name, avoids duplicates and path traversal */
    public String fileName(byte[] data, String originalName) {
        String hash = sha256(data).substring(0, 8);
        return hash + "-" + safeName(originalName) + ".epub";
    }

    /** Category name to directory name: strips path separators and illegal characters, keeps letters, digits, underscore, dot and dash */
    public String safeDirName(String categoryName) {
        String name = categoryName == null ? "" : categoryName.trim();
        name = name.replaceAll("[\\\\/:*?\"<>|]+", "_");
        name = name.replaceAll("\\s+", "_");
        if (name.length() > 80) {
            name = name.substring(0, 80);
        }
        name = name.replaceAll("^[_.-]+", "").replaceAll("[_.-]+$", "");
        return name.isBlank() ? "Uncategorized" : name;
    }

    /** Full path, for logging */
    public String describe(String relativePath) {
        return relativePath == null ? "-" : dir.resolve(relativePath).toString();
    }

    private static boolean isEpub(Path path) {
        return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".epub");
    }

    private String safeName(String originalName) {
        String name = originalName == null ? "" : originalName;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        name = name.replaceAll("[^\\w\\u4e00-\\u9fa5.-]", "_").replaceAll("_{2,}", "_");
        name = name.replaceAll("^[_.-]+", "");
        if (name.length() > 60) {
            name = name.substring(0, 60);
        }
        return name.isBlank() ? "book" : name;
    }
}
