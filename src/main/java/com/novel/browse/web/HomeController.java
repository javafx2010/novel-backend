package com.novel.browse.web;

import com.novel.browse.domain.Category;
import com.novel.browse.domain.Novel;
import com.novel.browse.service.ChapterService;
import com.novel.browse.service.NovelService;
import com.novel.browse.web.dto.CategoryDto;
import com.novel.browse.web.dto.ChapterItem;
import com.novel.browse.web.dto.HomeVo;
import com.novel.browse.web.dto.NovelSummary;
import com.novel.browse.web.dto.PageResult;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Home aggregates, category library, ranking and search (REST APIs).
 */
@RestController
public class HomeController {

    private static final String DEFAULT_SORT = "hot";

    private final NovelService novelService;
    private final ChapterService chapterService;

    public HomeController(NovelService novelService, ChapterService chapterService) {
        this.novelService = novelService;
        this.chapterService = chapterService;
    }

    /** Home page aggregates */
    @GetMapping("/home")
    public HomeVo home() {
        List<Novel> recommends = novelService.recommendList();
        List<Novel> hot = novelService.hotList();
        List<Novel> latest = novelService.latestUpdatedList();
        List<Novel> finished = novelService.finishedPage(0, 6).getContent();

        return new HomeVo(
                novelService.countAll(),
                novelService.listCategories().stream().map(CategoryDto::of).toList(),
                toSummaries(recommends),
                toSummaries(hot),
                toSummaries(finished),
                toUpdates(latest));
    }

    /** All categories */
    @GetMapping("/categories")
    public List<CategoryDto> categories() {
        return novelService.listCategories().stream().map(CategoryDto::of).toList();
    }

    /** Category detail */
    @GetMapping("/categories/{id}")
    public CategoryDto category(@PathVariable Long id) {
        return CategoryDto.of(novelService.getCategory(id));
    }

    /** Category library, sort supports hot(popularity) / update(recency), {@code language} restricts the version */
    @GetMapping("/categories/{id}/novels")
    public PageResult<NovelSummary> novelsOfCategory(@PathVariable Long id,
                                                     @RequestParam(defaultValue = DEFAULT_SORT) String sort,
                                                     @RequestParam(required = false) String language,
                                                     @RequestParam(defaultValue = "0") int page,
                                                     @RequestParam(defaultValue = "12") int size) {
        Category category = novelService.getCategory(id);
        return PageResult.of(novelService.pageByCategory(category, sort, language, page, size), NovelSummary::of);
    }

    /** All novels, filterable by category and language */
    @GetMapping("/novels")
    public PageResult<NovelSummary> novels(@RequestParam(required = false) Long categoryId,
                                           @RequestParam(defaultValue = DEFAULT_SORT) String sort,
                                           @RequestParam(required = false) String language,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "12") int size) {
        Page<Novel> novels;
        if (categoryId != null) {
            novels = novelService.pageByCategory(novelService.getCategory(categoryId), sort, language, page, size);
        } else {
            novels = novelService.rankPage(sort, language, page, size);
        }
        return PageResult.of(novels, NovelSummary::of);
    }

    /** Ranking, type supports hot(popularity) / rating(score), {@code language} restricts the version */
    @GetMapping("/rank")
    public PageResult<NovelSummary> rank(@RequestParam(defaultValue = "hot") String type,
                                         @RequestParam(required = false) String language,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        return PageResult.of(novelService.rankPage(type, language, page, size), NovelSummary::of);
    }

    /** Keyword search: title / author / tags, optionally restricted to one language */
    @GetMapping("/search")
    public PageResult<NovelSummary> search(@RequestParam(value = "q", required = false) String keyword,
                                           @RequestParam(required = false) String language,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "12") int size) {
        if (keyword == null || keyword.isBlank()) {
            return PageResult.empty(page, size);
        }
        return PageResult.of(novelService.search(keyword.trim(), language, page, size), NovelSummary::of);
    }

    private List<NovelSummary> toSummaries(List<Novel> novels) {
        return novels.stream().map(NovelSummary::of).toList();
    }

    private List<HomeVo.NovelUpdate> toUpdates(List<Novel> novels) {
        return novels.stream()
                .map(novel -> new HomeVo.NovelUpdate(NovelSummary.of(novel),
                        chapterService.lastChapter(novel.getId()).map(ChapterItem::of).orElse(null)))
                .toList();
    }
}
