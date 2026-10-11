package com.novel.browse.service;

import com.novel.browse.cache.CacheNames;
import com.novel.browse.domain.Category;
import com.novel.browse.domain.Novel;
import com.novel.browse.i18n.BookLanguage;
import com.novel.browse.i18n.I18nSettings;
import com.novel.browse.repository.CategoryRepository;
import com.novel.browse.repository.NovelRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

@Service
@Transactional(readOnly = true)
public class NovelService {

    /** Fallback when no category name is supplied */
    private static final String DEFAULT_CATEGORY_NAME = "Imported";

    private static final Logger log = LoggerFactory.getLogger(NovelService.class);

    private final NovelRepository novelRepository;
    private final CategoryRepository categoryRepository;
    private final I18nSettings settings;

    public NovelService(NovelRepository novelRepository, CategoryRepository categoryRepository,
                        I18nSettings settings) {
        this.novelRepository = novelRepository;
        this.categoryRepository = categoryRepository;
        this.settings = settings;
    }

    /** Every category of every language, in navigation order */
    public List<Category> listCategories() {
        return categoryRepository.findAllByOrderBySortNoAsc();
    }

    /**
     * Categories of one language, in navigation order.
     *
     * @param language language tag ({@code en} / {@code zh_CN}…), blank for every language
     */
    public List<Category> listCategories(String language) {
        if (language == null || language.isBlank()) {
            return listCategories();
        }
        return categoryRepository.findAllByLanguageOrderBySortNoAsc(BookLanguage.normalize(language, settings));
    }

    public Category getCategory(Long id) {
        return categoryRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("error.category.notFound", id));
    }

    /**
     * Finds the category of one language and creates it when missing: the site ships without any seed data,
     * so categories appear when the first book of that language is imported (upload, MCP or startup scan).
     *
     * @param name        category name, blank falls back to the default name
     * @param language    language of the category (the language of the book being imported)
     * @param description description in that language, applied when the category is created and updated
     *                    when a different one is supplied later; blank keeps the current text
     */
    @Transactional
    public Category findOrCreateCategory(String name, String language, String description) {
        String target = name == null || name.isBlank() ? DEFAULT_CATEGORY_NAME : name.trim();
        String tag = BookLanguage.normalize(language, settings);
        Category category = categoryRepository.findByNameAndLanguage(target, tag).orElseGet(() -> {
            int maxSort = categoryRepository.findAllByOrderBySortNoAsc().stream()
                    .map(Category::getSortNo)
                    .filter(Objects::nonNull)
                    .mapToInt(Integer::intValue)
                    .max()
                    .orElse(0);
            Category created = categoryRepository.save(new Category(target, "Created on import", maxSort + 1, tag));
            log.info("Category created on import: {} (id={}, language={}, sortNo={})", target, created.getId(), tag,
                    created.getSortNo());
            return created;
        });
        if (description != null && !description.isBlank()
                && !description.trim().equals(category.getDescription())) {
            category.setDescription(description.trim());
            categoryRepository.save(category);
        }
        return category;
    }

    @Cacheable(cacheNames = CacheNames.NOVEL_DETAIL, key = "#id")
    public Novel detail(Long id) {
        return novelRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("error.novel.notFound", id));
    }

    @Transactional
    @CacheEvict(cacheNames = CacheNames.NOVEL_DETAIL, key = "#id")
    public Novel viewDetail(Long id) {
        Novel novel = detail(id);
        novel.setViewCount(novel.getViewCount() + 1);
        return novelRepository.save(novel);
    }

    /** Hot ranking: ordered by view count; {@code language} restricts the version */
    @Cacheable(cacheNames = CacheNames.HOME_LIST, key = "'hot:' + #language")
    public List<Novel> hotList(String language) {
        return top(language, 10, Sort.by(Sort.Direction.DESC, "viewCount"));
    }

    /** Featured: ordered by rating; {@code language} restricts the version */
    @Cacheable(cacheNames = CacheNames.HOME_LIST, key = "'recommend:' + #language")
    public List<Novel> recommendList(String language) {
        return top(language, 8, Sort.by(Sort.Direction.DESC, "rating", "viewCount"));
    }

    /** Latest updates; {@code language} restricts the version */
    @Cacheable(cacheNames = CacheNames.HOME_LIST, key = "'latest:' + #language")
    public List<Novel> latestUpdatedList(String language) {
        return top(language, 8, Sort.by(Sort.Direction.DESC, "updatedAt"));
    }

    /** Completed novels recommendation; {@code language} restricts the version */
    public Page<Novel> finishedPage(String language, int page, int size) {
        Specification<Novel> finished = (root, query, builder) ->
                builder.equal(root.get("status"), Novel.STATUS_FINISHED);
        return novelRepository.findAll(languageSpec(language).and(finished),
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "viewCount")));
    }

    /** First {@code size} novels of a listing, restricted to one language when it is given */
    private List<Novel> top(String language, int size, Sort sort) {
        return novelRepository.findAll(languageSpec(language), PageRequest.of(0, size, sort)).getContent();
    }

    /**
     * Category library, sort supports hot(popularity) / update(recency).
     *
     * @param language restrict to one language version, blank for every language
     */
    public Page<Novel> pageByCategory(Category category, String sort, String language, int page, int size) {
        Sort ordering = "update".equalsIgnoreCase(sort)
                ? Sort.by(Sort.Direction.DESC, "updatedAt")
                : Sort.by(Sort.Direction.DESC, "viewCount");
        Pageable pageable = PageRequest.of(page, size, ordering);
        return novelRepository.findAll(languageSpec(language).and(categorySpec(category)), pageable);
    }

    /**
     * Ranking: ordered by view count by default, by rating when type=rating.
     *
     * @param language restrict to one language version, blank for every language
     */
    public Page<Novel> rankPage(String type, String language, int page, int size) {
        Sort sort = "rating".equalsIgnoreCase(type)
                ? Sort.by(Sort.Direction.DESC, "rating", "viewCount")
                : Sort.by(Sort.Direction.DESC, "viewCount");
        return novelRepository.findAll(languageSpec(language), PageRequest.of(page, size, sort));
    }

    /** Keyword search: title / author / tags, optionally restricted to one language */
    public Page<Novel> search(String keyword, String language, int page, int size) {
        if (keyword == null || keyword.isBlank()) {
            return Page.empty();
        }
        String pattern = "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%";
        Specification<Novel> keywordSpec = (root, query, builder) -> builder.or(
                builder.like(builder.lower(root.get("title")), pattern),
                builder.like(builder.lower(root.get("author")), pattern),
                builder.like(builder.lower(root.get("tags")), pattern));
        return novelRepository.findAll(languageSpec(language).and(keywordSpec), PageRequest.of(page, size));
    }

    /**
     * Every language version of the same work, the requested one included.
     * A book imported before multi language support has no group and is its only version.
     */
    @Transactional(readOnly = true)
    public List<Novel> versions(Long id) {
        Novel novel = novelRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("error.novel.notFound", id));
        String group = novel.getVersionGroup();
        if (group == null || group.isBlank()) {
            return List.of(novel);
        }
        return novelRepository.findByVersionGroupOrderByLanguage(group);
    }

    /** Restricts a listing to one language; blank means every language */
    private Specification<Novel> languageSpec(String language) {
        if (language == null || language.isBlank()) {
            return (root, query, builder) -> builder.conjunction();
        }
        String tag = BookLanguage.normalize(language, settings);
        return (root, query, builder) -> builder.equal(root.get("language"), tag);
    }

    private static Specification<Novel> categorySpec(Category category) {
        return (root, query, builder) -> builder.equal(root.get("category"), category);
    }

    /**
     * Same category recommendation: the language of the viewed edition only, and never another language
     * version of the same work (those are reachable through {@code GET /novels/{id}/versions}).
     */
    @Cacheable(cacheNames = CacheNames.HOME_LIST, key = "'related:' + #novel.id + ':' + #limit")
    public List<Novel> relatedList(Novel novel, int limit) {
        if (novel.getId() == null || novel.getCategory() == null) {
            return List.of();
        }
        String language = novel.getLanguage();
        String group = novel.getVersionGroup();
        return novelRepository.findTop6ByCategoryAndIdNotOrderByViewCountDesc(
                        novel.getCategory(), novel.getId()).stream()
                .filter(item -> language == null || language.equalsIgnoreCase(item.getLanguage()))
                .filter(item -> group == null || !group.equalsIgnoreCase(item.getVersionGroup()))
                .limit(limit)
                .toList();
    }

    /** Number of novels, restricted to one language version when it is given */
    public long countAll(String language) {
        return novelRepository.count(languageSpec(language));
    }
}
