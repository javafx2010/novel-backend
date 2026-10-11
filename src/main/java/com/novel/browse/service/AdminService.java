package com.novel.browse.service;

import com.novel.browse.cache.CacheNames;
import com.novel.browse.domain.Category;
import com.novel.browse.domain.Chapter;
import com.novel.browse.domain.Novel;
import com.novel.browse.repository.CategoryRepository;
import com.novel.browse.repository.ChapterRepository;
import com.novel.browse.repository.NovelRepository;
import com.novel.browse.service.epub.EpubBook;
import com.novel.browse.service.epub.EpubChapter;
import com.novel.browse.service.epub.EpubParser;
import com.novel.browse.service.epub.EpubStorage;
import com.novel.browse.service.epub.TextUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Admin: EPUB upload (source file stored on disk), startup directory scan import, novel deletion and listing.
 * <p>
 * Writes keep chapter count, word count and update time in a single transaction and evict the affected caches,
 * so the home page, detail page and chapters always read fresh data.
 */
@Service
public class AdminService {

    private static final Logger log = LoggerFactory.getLogger(AdminService.class);

    /** Default cover colors for uploaded books (no image resources, gradient plus first character) */
    private static final String[] COVER_COLORS = {
            "linear-gradient(135deg,#6a8cff,#8f6cff)",
            "linear-gradient(135deg,#ff7a59,#ffb26b)",
            "linear-gradient(135deg,#3ec9a7,#57b7ff)",
            "linear-gradient(135deg,#ff6b9d,#c86bff)",
            "linear-gradient(135deg,#f5c451,#f78b5a)",
            "linear-gradient(135deg,#5b8def,#3ec9c1)",
    };

    private final EpubParser epubParser;
    private final EpubStorage epubStorage;
    private final CoverStorage coverStorage;
    private final NovelRepository novelRepository;
    private final ChapterRepository chapterRepository;
    private final CategoryRepository categoryRepository;

    public AdminService(EpubParser epubParser,
                        EpubStorage epubStorage,
                        CoverStorage coverStorage,
                        NovelRepository novelRepository,
                        ChapterRepository chapterRepository,
                        CategoryRepository categoryRepository) {
        this.epubParser = epubParser;
        this.epubStorage = epubStorage;
        this.coverStorage = coverStorage;
        this.novelRepository = novelRepository;
        this.chapterRepository = chapterRepository;
        this.categoryRepository = categoryRepository;
    }

    /**
     * Uploads an EPUB: store on disk, then parse, then persist.
     *
     * @param data       raw EPUB content
     * @param fileName   original file name
     * @param categoryId category id
     * @param status     serialization status (ONGOING when blank)
     * @param tags       tags (falls back to the EPUB dc:subject when blank)
     */
    @Transactional
    @CacheEvict(cacheNames = CacheNames.HOME_LIST, allEntries = true)
    public Novel upload(byte[] data, String fileName, Long categoryId, String status, String tags) {
        String hash = epubStorage.sha256(data);
        if (novelRepository.existsBySourceHash(hash)) {
            throw new BadRequestException("This EPUB was already imported (content hash " + hash.substring(0, 8) + ")");
        }
        Category category = categoryRepository.findById(categoryId)
                .orElseThrow(() -> new NotFoundException("Category not found: id=" + categoryId));

        // Store under the category directory: data/epub/<category>/<hash>-<original name>.epub
        String savedPath = epubStorage.save(data, fileName, category.getName());
        try {
            Novel novel = createNovel(data, fileName, category, status, tags, hash);
            novel.setSourceFile(savedPath);
            novel.setSourceHash(hash);
            return novelRepository.save(novel);
        } catch (RuntimeException e) {
            // On parse or persistence failure remove the stored file so the next startup scan does not import it again
            epubStorage.delete(savedPath);
            throw e;
        }
    }

    /**
     * Imports an EPUB file from disk (used by the startup scan, no deduplication here).
     *
     * @param file     EPUB file
     * @param hash     content hash computed by the caller, stored in source_hash
     * @param category owning category
     */
    @Transactional
    @CacheEvict(cacheNames = CacheNames.HOME_LIST, allEntries = true)
    public Novel importFile(Path file, String hash, Category category, String status) {
        byte[] data;
        try {
            data = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read the EPUB: " + file, e);
        }
        Novel novel = createNovel(data, file.getFileName().toString(), category, status, null, hash);
        novel.setSourceFile(epubStorage.relative(file));
        novel.setSourceHash(hash);
        return novelRepository.save(novel);
    }

    /** Admin novel list: newest update first */
    @Transactional(readOnly = true)
    public List<Novel> listNovels() {
        return novelRepository.findAll(Sort.by(Sort.Direction.DESC, "updatedAt"));
    }

    /** Deletes a novel with its chapters and removes the source EPUB from disk */
    @Transactional
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheNames.HOME_LIST, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.NOVEL_DETAIL, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.CHAPTER_LIST, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.CHAPTER, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.CHAPTER_NO, allEntries = true)
    })
    public void deleteNovel(Long id) {
        Novel novel = novelRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Novel not found: id=" + id));
        int removed = chapterRepository.deleteByNovelId(id);
        novelRepository.delete(novel);
        String sourceFile = novel.getSourceFile();
        if (sourceFile != null && !sourceFile.isBlank()) {
            epubStorage.delete(sourceFile);
        }
        coverStorage.delete(novel.getCoverImage());
        log.info("Deleted novel: id={} title={} chapters={} sourceFile={}", id, novel.getTitle(), removed,
                sourceFile == null ? "none" : sourceFile);
    }

    /**
     * Updates the metadata of a novel: only non-blank arguments are applied, the rest is left untouched.
     *
     * @param id          novel id
     * @param title       new title (optional)
     * @param author      new author (optional)
     * @param description new description (optional)
     * @param status      {@code ONGOING} / {@code FINISHED} (optional)
     * @param tags        comma separated tags (optional)
     * @param categoryId  target category (optional)
     */
    @Transactional
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheNames.HOME_LIST, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.NOVEL_DETAIL, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.CHAPTER_LIST, allEntries = true)
    })
    public Novel updateNovel(Long id, String title, String author, String description,
                             String status, String tags, Long categoryId) {
        Novel novel = novelRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Novel not found: id=" + id));
        if (title != null && !title.isBlank()) {
            novel.setTitle(TextUtil.truncate(title.trim(), 128));
        }
        if (author != null && !author.isBlank()) {
            novel.setAuthor(TextUtil.truncate(author.trim(), 64));
        }
        if (description != null && !description.isBlank()) {
            novel.setDescription(TextUtil.truncate(description.trim(), 2000));
        }
        if (tags != null && !tags.isBlank()) {
            novel.setTags(TextUtil.truncate(tags.trim(), 128));
        }
        if (status != null && !status.isBlank()) {
            novel.setStatus(Novel.STATUS_FINISHED.equalsIgnoreCase(status.trim())
                    ? Novel.STATUS_FINISHED : Novel.STATUS_ONGOING);
        }
        if (categoryId != null) {
            novel.setCategory(categoryRepository.findById(categoryId)
                    .orElseThrow(() -> new NotFoundException("Category not found: id=" + categoryId)));
        }
        novel.setUpdatedAt(LocalDateTime.now());
        Novel saved = novelRepository.save(novel);
        log.info("Novel updated: id={} title={} category={} status={}", saved.getId(), saved.getTitle(),
                saved.getCategory() == null ? null : saved.getCategory().getName(), saved.getStatus());
        return saved;
    }

    /**
     * Parses the EPUB and builds the novel with its chapters: maintains chapter count, word count, cover and tags.
     * The caller sets the source file information and persists the result.
     */
    private Novel createNovel(byte[] data, String fileName, Category category, String status, String tags,
                              String hash) {
        EpubBook book = epubParser.parse(data, fileName);

        String title = defaultIfBlank(book.title(), defaultTitle(fileName));
        Novel novel = new Novel();
        novel.setTitle(TextUtil.truncate(title, 128));
        novel.setAuthor(TextUtil.truncate(defaultIfBlank(book.author(), "Unknown"), 64));
        novel.setCategory(category);
        novel.setCoverColor(COVER_COLORS[Math.abs(title.hashCode()) % COVER_COLORS.length]);
        novel.setCoverMark(coverMark(title));
        if (book.coverImage() != null && book.coverImage().length > 0) {
            novel.setCoverImage(coverStorage.save(book.coverImage(), book.coverMediaType(), hash));
        }
        novel.setDescription(TextUtil.truncate(book.description(), 2000));
        novel.setTags(TextUtil.truncate(tags != null && !tags.isBlank() ? tags : String.join(",", book.subjects()), 128));
        novel.setStatus(Novel.STATUS_FINISHED.equalsIgnoreCase(status) ? Novel.STATUS_FINISHED : Novel.STATUS_ONGOING);
        novel.setViewCount(0L);
        novel.setRating(0.0);
        novel.setChapterCount(0);
        novel.setWordCount(0L);

        long words = 0L;
        List<Chapter> chapters = new ArrayList<>(book.chapters().size());
        LocalDateTime now = LocalDateTime.now();
        int no = 0;
        for (EpubChapter source : book.chapters()) {
            String text = source.text();
            long wordCount = TextUtil.wordCount(text);
            words += wordCount;
            Chapter chapter = new Chapter();
            chapter.setNovel(novel);
            chapter.setChapterNo(++no);
            chapter.setTitle(TextUtil.truncate(source.title(), 200));
            chapter.setContent(text);
            chapter.setWordCount((int) wordCount);
            chapter.setCreatedAt(now);
            chapters.add(chapter);
        }
        novel.setChapterCount(chapters.size());
        novel.setWordCount(words);
        novel.setCreatedAt(now);
        novel.setUpdatedAt(now);

        Novel saved = novelRepository.save(novel);
        chapterRepository.saveAll(chapters);
        log.info("EPUB imported: id={} title={} chapters={} words={}", saved.getId(), saved.getTitle(), chapters.size(), words);
        return saved;
    }

    private String coverMark(String title) {
        for (char c : title.toCharArray()) {
            if (!Character.isWhitespace(c)) {
                return String.valueOf(c);
            }
        }
        return "B";
    }

    private String defaultTitle(String fileName) {
        String name = fileName == null ? "" : fileName;
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        return name.isBlank() ? "Untitled" : name;
    }

    private String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
