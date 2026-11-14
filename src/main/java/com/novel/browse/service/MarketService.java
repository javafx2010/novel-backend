package com.novel.browse.service;

import com.novel.browse.domain.Novel;
import com.novel.browse.repository.NovelRepository;
import com.novel.browse.service.epub.EpubStorage;
import com.novel.browse.web.dto.MarketBook;
import com.novel.browse.web.dto.PageResult;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Book market: lists the books that can be downloaded to a local device and streams their EPUB.
 * <p>
 * There is no purchase step and no entitlement check &mdash; a book is "on the market" exactly when a
 * source EPUB is stored for it ({@code nb_novel.source_file}). The download endpoint just hands the
 * original file back to the caller.
 */
@Service
@Transactional(readOnly = true)
public class MarketService {

    private static final String EPUB_SUFFIX = ".epub";

    private final NovelRepository novelRepository;
    private final NovelService novelService;
    private final EpubStorage epubStorage;

    public MarketService(NovelRepository novelRepository, NovelService novelService, EpubStorage epubStorage) {
        this.novelRepository = novelRepository;
        this.novelService = novelService;
        this.epubStorage = epubStorage;
    }

    /** One downloadable book resolved to a file on disk */
    public record Download(Path file, String fileName, long size) {
    }

    /**
     * Market listing: only books with a stored EPUB, optionally restricted by language / category / keyword.
     *
     * @param language   language tag, blank for the request language, {@code all} for every version
     * @param categoryId restrict to one category, {@code null} for every category
     * @param keyword    matches title / author / tags, blank for no keyword
     * @param sort       {@code update} (default) / {@code hot} / {@code rating}
     */
    public PageResult<MarketBook> page(String language, Long categoryId, String keyword, String sort,
                                       int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = size <= 0 ? 20 : Math.min(size, 100);
        Specification<Novel> spec = hasSourceFile()
                .and(languageSpec(novelService.effectiveLanguage(language)))
                .and(categorySpec(categoryId))
                .and(keywordSpec(keyword));
        Page<Novel> result = novelRepository.findAll(spec, PageRequest.of(safePage, safeSize, ordering(sort)));
        return PageResult.of(result, this::toMarketBook);
    }

    /** Market entry of one book; 404 when the book does not exist or has no downloadable file */
    public MarketBook detail(Long id) {
        Novel novel = novelService.detail(id);
        if (!downloadable(novel)) {
            throw new NotFoundException("error.market.notDownloadable", id);
        }
        return toMarketBook(novel);
    }

    /**
     * Resolves the EPUB file of one book for download.
     *
     * @throws NotFoundException when the book does not exist, has no source file, or the file is missing
     */
    public Download download(Long id) {
        Novel novel = novelService.detail(id);
        Path file = epubStorage.resolve(novel.getSourceFile());
        if (file == null || !Files.isRegularFile(file)) {
            throw new NotFoundException("error.market.fileMissing", id);
        }
        try {
            return new Download(file, downloadName(novel), Files.size(file));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read the EPUB size: " + file, e);
        }
    }

    private MarketBook toMarketBook(Novel novel) {
        return MarketBook.of(novel, downloadName(novel), fileSize(novel));
    }

    private Long fileSize(Novel novel) {
        Path file = epubStorage.resolve(novel.getSourceFile());
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try {
            return Files.size(file);
        } catch (IOException e) {
            return null;
        }
    }

    /** Suggested download name: the book title, or the stored file name when the title is blank */
    private String downloadName(Novel novel) {
        String base = safeName(novel.getTitle());
        if (base.isBlank()) {
            String stored = novel.getSourceFile();
            int slash = stored == null ? -1 : Math.max(stored.lastIndexOf('/'), stored.lastIndexOf('\\'));
            base = slash >= 0 ? stored.substring(slash + 1) : stored;
            base = safeName(base);
        }
        if (base.isBlank()) {
            base = "book-" + novel.getId();
        }
        return base.toLowerCase(Locale.ROOT).endsWith(EPUB_SUFFIX) ? base : base + EPUB_SUFFIX;
    }

    /** Strips characters that are illegal in a file name and trims the extension / separators */
    private static String safeName(String raw) {
        if (raw == null) {
            return "";
        }
        String name = raw.trim().replaceAll("[\\\\/:*?\"<>|\\r\\n]+", "_");
        if (name.toLowerCase(Locale.ROOT).endsWith(EPUB_SUFFIX)) {
            name = name.substring(0, name.length() - EPUB_SUFFIX.length());
        }
        name = name.replaceAll("^[.\\s]+", "").replaceAll("[.\\s]+$", "");
        return name.length() > 120 ? name.substring(0, 120) : name;
    }

    private static boolean downloadable(Novel novel) {
        return novel.getSourceFile() != null && !novel.getSourceFile().isBlank();
    }

    private static Specification<Novel> hasSourceFile() {
        return (root, query, builder) -> builder.and(
                builder.isNotNull(root.get("sourceFile")),
                builder.notEqual(root.get("sourceFile"), ""));
    }

    private static Specification<Novel> languageSpec(String language) {
        if (NovelService.ALL_LANGUAGES.equals(language)) {
            return (root, query, builder) -> builder.conjunction();
        }
        return (root, query, builder) -> builder.equal(root.get("language"), language);
    }

    private Specification<Novel> categorySpec(Long categoryId) {
        if (categoryId == null) {
            return (root, query, builder) -> builder.conjunction();
        }
        var category = novelService.getCategory(categoryId);
        return (root, query, builder) -> builder.equal(root.get("category"), category);
    }

    private static Specification<Novel> keywordSpec(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return (root, query, builder) -> builder.conjunction();
        }
        String pattern = "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%";
        return (root, query, builder) -> builder.or(
                builder.like(builder.lower(root.get("title")), pattern),
                builder.like(builder.lower(root.get("author")), pattern),
                builder.like(builder.lower(root.get("tags")), pattern));
    }

    private static Sort ordering(String sort) {
        if ("hot".equalsIgnoreCase(sort)) {
            return Sort.by(Sort.Direction.DESC, "viewCount");
        }
        if ("rating".equalsIgnoreCase(sort)) {
            return Sort.by(Sort.Direction.DESC, "rating", "viewCount");
        }
        return Sort.by(Sort.Direction.DESC, "updatedAt");
    }
}
