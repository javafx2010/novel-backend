package com.novel.browse.service;

import com.novel.browse.domain.Category;
import com.novel.browse.domain.Chapter;
import com.novel.browse.domain.Novel;
import com.novel.browse.repository.ChapterRepository;
import com.novel.browse.repository.NovelRepository;
import com.novel.browse.service.epub.EpubStorage;
import com.novel.browse.service.epub.MarketEpubBuilder;
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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Book market: lists the books that can be downloaded to a local device and streams their EPUB.
 * <p>
 * There is no purchase step and no entitlement check. A book is on the market when a source EPUB is
 * stored for it ({@code nb_novel.source_file}) <b>or</b> it has at least one chapter (books imported
 * chapter-by-chapter, e.g. crawled news). For the latter the download endpoint builds an EPUB on the
 * fly with {@link MarketEpubBuilder}, so the client always receives a real {@code .epub}.
 */
@Service
@Transactional(readOnly = true)
public class MarketService {

    private static final String EPUB_SUFFIX = ".epub";

    private final NovelRepository novelRepository;
    private final NovelService novelService;
    private final EpubStorage epubStorage;
    private final ChapterRepository chapterRepository;
    private final MarketEpubBuilder epubBuilder;

    public MarketService(NovelRepository novelRepository, NovelService novelService, EpubStorage epubStorage,
                         ChapterRepository chapterRepository, MarketEpubBuilder epubBuilder) {
        this.novelRepository = novelRepository;
        this.novelService = novelService;
        this.epubStorage = epubStorage;
        this.chapterRepository = chapterRepository;
        this.epubBuilder = epubBuilder;
    }

    /** One downloadable book: either a file on disk, or an EPUB generated in memory from its chapters. */
    public record Download(Path file, byte[] bytes, String fileName, long size) {
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
        Specification<Novel> spec = downloadable()
                .and(languageSpec(novelService.effectiveLanguage(language)))
                .and(categorySpec(categoryId))
                .and(keywordSpec(keyword));
        Page<Novel> result = novelRepository.findAll(spec, PageRequest.of(safePage, safeSize, ordering(sort)));
        return PageResult.of(result, this::toMarketBook);
    }

    /** Categories that have at least one downloadable book, ordered by their sort number. */
    public List<Category> categories(String language) {
        Specification<Novel> spec = downloadable()
                .and(languageSpec(novelService.effectiveLanguage(language)));
        Map<Long, Category> byId = new LinkedHashMap<>();
        for (Novel novel : novelRepository.findAll(spec)) {
            Category category = novel.getCategory();
            if (category != null) {
                byId.putIfAbsent(category.getId(), category);
            }
        }
        return byId.values().stream()
                .sorted(Comparator.comparingInt((Category c) -> c.getSortNo() == null ? 0 : c.getSortNo()))
                .toList();
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
     * Resolves the EPUB of one book for download: the stored source file when present, otherwise an
     * EPUB generated from the book's chapters.
     *
     * @throws NotFoundException when the book has neither a source file nor any chapter
     */
    public Download download(Long id) {
        Novel novel = novelService.detail(id);
        String fileName = downloadName(novel);

        Path file = epubStorage.resolve(novel.getSourceFile());
        if (file != null && Files.isRegularFile(file)) {
            try {
                return new Download(file, null, fileName, Files.size(file));
            } catch (IOException e) {
                throw new IllegalStateException("Failed to read the EPUB size: " + file, e);
            }
        }

        List<Chapter> chapters = chapterRepository.findByNovelIdOrderByChapterNoAsc(id);
        if (chapters.isEmpty()) {
            throw new NotFoundException("error.market.notDownloadable", id);
        }
        byte[] bytes = epubBuilder.build(novel, chapters);
        return new Download(null, bytes, fileName, bytes.length);
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
        boolean hasFile = novel.getSourceFile() != null && !novel.getSourceFile().isBlank();
        boolean hasChapters = novel.getChapterCount() != null && novel.getChapterCount() > 0;
        return hasFile || hasChapters;
    }

    /** Books with a stored EPUB file or at least one chapter (an EPUB can be generated for those). */
    private static Specification<Novel> downloadable() {
        return (root, query, builder) -> builder.or(
                builder.and(
                        builder.isNotNull(root.get("sourceFile")),
                        builder.notEqual(root.get("sourceFile"), "")),
                builder.greaterThan(root.get("chapterCount"), 0));
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
