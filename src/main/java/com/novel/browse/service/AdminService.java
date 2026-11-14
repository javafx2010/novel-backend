package com.novel.browse.service;

import com.novel.browse.cache.CacheNames;
import com.novel.browse.domain.Category;
import com.novel.browse.domain.Chapter;
import com.novel.browse.domain.Novel;
import com.novel.browse.i18n.BookLanguage;
import com.novel.browse.i18n.I18nSettings;
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
import org.springframework.beans.factory.annotation.Value;
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
import java.util.Optional;
import java.util.UUID;

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
    private final NovelService novelService;
    private final NovelRepository novelRepository;
    private final ChapterRepository chapterRepository;
    private final CategoryRepository categoryRepository;
    private final I18nSettings settings;
    private final String defaultCategoryName;

    public AdminService(EpubParser epubParser,
                        EpubStorage epubStorage,
                        CoverStorage coverStorage,
                        NovelService novelService,
                        NovelRepository novelRepository,
                        ChapterRepository chapterRepository,
                        CategoryRepository categoryRepository,
                        I18nSettings settings,
                        @Value("${app.upload.default-category-name:Imported}") String defaultCategoryName) {
        this.epubParser = epubParser;
        this.epubStorage = epubStorage;
        this.coverStorage = coverStorage;
        this.novelService = novelService;
        this.novelRepository = novelRepository;
        this.chapterRepository = chapterRepository;
        this.categoryRepository = categoryRepository;
        this.settings = settings;
        this.defaultCategoryName = defaultCategoryName;
    }

    /**
     * Uploads an EPUB: store on disk, then parse, then persist.
     * <p>
     * A book already in the library (same title, author and language) is overwritten instead of duplicated;
     * the same work in another language is imported as a separate edition and joins its version group.
     *
     * @param data                raw EPUB content
     * @param fileName            original file name
     * @param categoryId          category id, takes precedence when present (must be of the same language)
     * @param categoryName        category name of that language, created when it does not exist yet
     * @param categoryDescription description of the category in that language (created / updated with it)
     * @param status              serialization status (ONGOING when blank)
     * @param tags                tags (falls back to the EPUB dc:subject when blank)
     * @param language            language of this edition ({@code en} / {@code zh_CN}); falls back to the EPUB
     *                            {@code dc:language}, then to {@code app.i18n.default-locale}
     * @param versionGroup        version group of an existing work the upload belongs to; generated when blank
     */
    @Transactional
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheNames.HOME_LIST, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.NOVEL_DETAIL, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.CHAPTER_LIST, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.CHAPTER, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.CHAPTER_NO, allEntries = true)
    })
    public Novel upload(byte[] data, String fileName, Long categoryId, String categoryName,
                        String categoryDescription, String status, String tags, String language,
                        String versionGroup) {
        String hash = epubStorage.sha256(data);
        if (novelRepository.existsBySourceHash(hash)) {
            throw new BadRequestException("error.epub.alreadyImported", hash.substring(0, 8));
        }
        EpubBook book = epubParser.parse(data, fileName);
        String edition = resolveLanguage(language, book.language());
        Category category = resolveCategory(categoryId, categoryName, categoryDescription, edition);

        // Store under the category directory: data/epub/<category>/<hash>-<original name>.epub
        String savedPath = epubStorage.save(data, fileName, category.getName());
        try {
            Novel novel = createNovel(book, fileName, category, status, tags, hash, edition, versionGroup);
            String previousFile = novel.getSourceFile();
            novel.setSourceFile(savedPath);
            novel.setSourceHash(hash);
            // Overwrite: the previously stored copy of the same book is replaced
            if (previousFile != null && !previousFile.isBlank() && !previousFile.equals(savedPath)) {
                epubStorage.delete(previousFile);
            }
            return novelRepository.save(novel);
        } catch (RuntimeException e) {
            // On persistence failure remove the stored file so the next startup scan does not import it again
            epubStorage.delete(savedPath);
            throw e;
        }
    }

    /**
     * Imports an EPUB file from disk (used by the startup scan; a book already in the library is overwritten).
     * The category is resolved in the language of the EPUB, so a directory holding books of several languages
     * feeds one category per language.
     *
     * @param file         EPUB file
     * @param hash         content hash computed by the caller, stored in source_hash
     * @param categoryName category name of the language of this file
     * @param status       serialization status
     */
    @Transactional
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheNames.HOME_LIST, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.NOVEL_DETAIL, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.CHAPTER_LIST, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.CHAPTER, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.CHAPTER_NO, allEntries = true)
    })
    public Novel importFile(Path file, String hash, String categoryName, String status) {
        byte[] data;
        try {
            data = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read the EPUB: " + file, e);
        }
        EpubBook book = epubParser.parse(data, file.getFileName().toString());
        // Language comes from the EPUB metadata (dc:language) or from app.i18n.default-locale
        String edition = resolveLanguage(null, book.language());
        Category category = resolveCategory(null, categoryName, null, edition);
        Novel novel = createNovel(book, file.getFileName().toString(), category, status, null, hash, edition, null);
        String previousFile = novel.getSourceFile();
        novel.setSourceFile(epubStorage.relative(file));
        novel.setSourceHash(hash);
        // Overwrite: the previously stored copy of the same book is replaced
        if (previousFile != null && !previousFile.isBlank() && !previousFile.equals(novel.getSourceFile())) {
            epubStorage.delete(previousFile);
        }
        return novelRepository.save(novel);
    }

    /** Admin novel list: newest update first */
    @Transactional(readOnly = true)
    public List<Novel> listNovels() {
        return novelRepository.findAll(Sort.by(Sort.Direction.DESC, "updatedAt"));
    }

    /**
     * Admin novel list restricted to one language version: newest update first.
     *
     * @param language language tag ({@code en} / {@code zh_CN}…); blank falls back to the language of the
     *                 request ({@code Accept-Language}) and then to {@code app.i18n.default-locale};
     *                 {@code all} lists every language version
     */
    @Transactional(readOnly = true)
    public List<Novel> listNovels(String language) {
        String tag = novelService.effectiveLanguage(language);
        if (NovelService.ALL_LANGUAGES.equals(tag)) {
            return listNovels();
        }
        return novelRepository.findAllByLanguageIgnoreCaseOrderByUpdatedAtDesc(tag);
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
                .orElseThrow(() -> new NotFoundException("error.novel.notFound", id));
        int removed = chapterRepository.deleteByNovelId(id);
        novelRepository.delete(novel);
        String sourceFile = novel.getSourceFile();
        if (sourceFile != null && !sourceFile.isBlank()) {
            epubStorage.delete(sourceFile);
        }
        coverStorage.delete(novel.getCoverImage());
        log.info("Deleted novel: id={} title={} chapters={} sourceFile={}", id, novel.getTitle(), removed,
                sourceFile == null ? "none" : sourceFile);
        novelService.purgeEmptyCategories();
    }

    /**
     * Category of an import: by id, else by name in the language of the book (created when missing),
     * else the default category of that language. A category of another language is rejected.
     */
    private Category resolveCategory(Long categoryId, String categoryName, String categoryDescription,
                                     String language) {
        if (categoryId != null) {
            Category category = categoryRepository.findById(categoryId)
                    .orElseThrow(() -> new NotFoundException("error.category.notFound", categoryId));
            if (!language.equalsIgnoreCase(category.getLanguage())) {
                throw new BadRequestException("error.category.languageMismatch", category.getLanguage(), language);
            }
            return category;
        }
        String name = categoryName == null || categoryName.isBlank() ? defaultCategoryName : categoryName.trim();
        return novelService.findOrCreateCategory(name, language, categoryDescription);
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
     * @param language    language of this edition, {@code en} / {@code zh_CN} (optional)
     */
    @Transactional
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheNames.HOME_LIST, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.NOVEL_DETAIL, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.CHAPTER_LIST, allEntries = true)
    })
    public Novel updateNovel(Long id, String title, String author, String description,
                             String status, String tags, Long categoryId, String language) {
        Novel novel = novelRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("error.novel.notFound", id));
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
        if (language != null && !language.isBlank()) {
            novel.setLanguage(resolveLanguage(language, null));
        }
        if (status != null && !status.isBlank()) {
            novel.setStatus(Novel.STATUS_FINISHED.equalsIgnoreCase(status.trim())
                    ? Novel.STATUS_FINISHED : Novel.STATUS_ONGOING);
        }
        if (categoryId != null) {
            Category category = categoryRepository.findById(categoryId)
                    .orElseThrow(() -> new NotFoundException("error.category.notFound", categoryId));
            if (!novel.getLanguage().equalsIgnoreCase(category.getLanguage())) {
                throw new BadRequestException("error.category.languageMismatch", category.getLanguage(),
                        novel.getLanguage());
            }
            novel.setCategory(category);
        }
        novel.setUpdatedAt(LocalDateTime.now());
        Novel saved = novelRepository.save(novel);
        log.info("Novel updated: id={} title={} category={} status={} language={}", saved.getId(), saved.getTitle(),
                saved.getCategory() == null ? null : saved.getCategory().getName(), saved.getStatus(), saved.getLanguage());
        return saved;
    }

    /**
     * Parses the EPUB and builds the novel with its chapters: maintains language, version group, chapter count,
     * word count, cover and tags. The caller sets the source file information and persists the result.
     */
    private Novel createNovel(EpubBook book, String fileName, Category category, String status, String tags,
                              String hash, String edition, String versionGroup) {
        String title = defaultIfBlank(book.title(), defaultTitle(fileName));
        String author = TextUtil.truncate(defaultIfBlank(book.author(), "Unknown"), 64);
        // Re-importing the same edition (same title, author and language) overwrites the existing entry instead of
        // adding a copy: the content hash is not enough, the same book re-generated with different bytes has a
        // different hash. Another language of the same work is a separate edition.
        Optional<Novel> existing = novelRepository
                .findFirstByTitleIgnoreCaseAndAuthorIgnoreCaseAndLanguageIgnoreCase(title, author, edition);
        boolean overwrite = existing.isPresent();
        Novel novel = existing.orElseGet(Novel::new);
        String previousCover = overwrite ? novel.getCoverImage() : null;

        novel.setLanguage(edition);
        novel.setVersionGroup(resolveVersionGroup(novel, overwrite, title, author, versionGroup));
        novel.setTitle(TextUtil.truncate(title, 128));
        novel.setAuthor(author);
        novel.setCategory(category);
        novel.setCoverColor(COVER_COLORS[Math.abs(title.hashCode()) % COVER_COLORS.length]);
        novel.setCoverMark(coverMark(title));
        if (book.coverImage() != null && book.coverImage().length > 0) {
            novel.setCoverImage(coverStorage.save(book.coverImage(), book.coverMediaType(), hash));
        }
        novel.setDescription(TextUtil.truncate(book.description(), 2000));
        novel.setTags(TextUtil.truncate(tags != null && !tags.isBlank() ? tags : String.join(",", book.subjects()), 128));
        novel.setStatus(Novel.STATUS_FINISHED.equalsIgnoreCase(status) ? Novel.STATUS_FINISHED : Novel.STATUS_ONGOING);
        if (!overwrite) {
            novel.setViewCount(0L);
            novel.setRating(0.0);
        }
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
        if (!overwrite) {
            novel.setCreatedAt(now);
        }
        novel.setUpdatedAt(now);

        Novel saved = novelRepository.save(novel);
        if (overwrite) {
            chapterRepository.deleteByNovelId(saved.getId());
            // flush before the new chapters are persisted: Hibernate would otherwise run the inserts first
            // and hit uk_chapter_novel_no
            chapterRepository.flush();
        }
        chapterRepository.saveAll(chapters);
        if (overwrite && previousCover != null && !previousCover.equals(novel.getCoverImage())) {
            coverStorage.delete(previousCover);
        }
        log.info("EPUB {}: id={} title={} language={} chapters={} words={}", overwrite ? "updated" : "imported",
                saved.getId(), saved.getTitle(), edition, chapters.size(), words);
        return saved;
    }

    /** Language of an edition: the request wins, then the EPUB {@code dc:language}, then the default locale */
    private String resolveLanguage(String requested, String epubLanguage) {
        String raw = requested != null && !requested.isBlank() ? requested : epubLanguage;
        return BookLanguage.normalize(raw, settings);
    }

    /**
     * Version group of an edition: the explicit one wins, else the group of the edition being overwritten,
     * else the group of another language version of the same work, else a brand new group.
     */
    private String resolveVersionGroup(Novel novel, boolean overwrite, String title, String author,
                                       String versionGroup) {
        if (versionGroup != null && !versionGroup.isBlank()) {
            return TextUtil.truncate(versionGroup.trim(), 64);
        }
        if (overwrite && novel.getVersionGroup() != null && !novel.getVersionGroup().isBlank()) {
            return novel.getVersionGroup();
        }
        Optional<Novel> sibling = novelRepository.findFirstByTitleIgnoreCaseAndAuthorIgnoreCase(title, author);
        if (sibling.isPresent()) {
            Novel other = sibling.get();
            if (other.getVersionGroup() == null || other.getVersionGroup().isBlank()) {
                // Imported before multi language support: give the whole work a group now
                other.setVersionGroup(UUID.randomUUID().toString());
                novelRepository.save(other);
            }
            return other.getVersionGroup();
        }
        return UUID.randomUUID().toString();
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
