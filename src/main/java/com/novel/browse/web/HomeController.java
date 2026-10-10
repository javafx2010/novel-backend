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
 * 首页聚合、分类书库、排行榜、搜索（REST 接口）。
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

    /** 首页聚合数据 */
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

    /** 全部分类 */
    @GetMapping("/categories")
    public List<CategoryDto> categories() {
        return novelService.listCategories().stream().map(CategoryDto::of).toList();
    }

    /** 分类详情 */
    @GetMapping("/categories/{id}")
    public CategoryDto category(@PathVariable Long id) {
        return CategoryDto.of(novelService.getCategory(id));
    }

    /** 分类书库，sort 支持 hot(人气) / update(更新) */
    @GetMapping("/categories/{id}/novels")
    public PageResult<NovelSummary> novelsOfCategory(@PathVariable Long id,
                                                     @RequestParam(defaultValue = DEFAULT_SORT) String sort,
                                                     @RequestParam(defaultValue = "0") int page,
                                                     @RequestParam(defaultValue = "12") int size) {
        Category category = novelService.getCategory(id);
        return PageResult.of(novelService.pageByCategory(category, sort, page, size), NovelSummary::of);
    }

    /** 全站书籍列表，可按分类筛选 */
    @GetMapping("/novels")
    public PageResult<NovelSummary> novels(@RequestParam(required = false) Long categoryId,
                                           @RequestParam(defaultValue = DEFAULT_SORT) String sort,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "12") int size) {
        Page<Novel> novels;
        if (categoryId != null) {
            novels = novelService.pageByCategory(novelService.getCategory(categoryId), sort, page, size);
        } else {
            novels = novelService.rankPage(sort, page, size);
        }
        return PageResult.of(novels, NovelSummary::of);
    }

    /** 排行榜，type 支持 hot(人气) / rating(评分) */
    @GetMapping("/rank")
    public PageResult<NovelSummary> rank(@RequestParam(defaultValue = "hot") String type,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        return PageResult.of(novelService.rankPage(type, page, size), NovelSummary::of);
    }

    /** 关键词搜索：书名 / 作者 / 标签 */
    @GetMapping("/search")
    public PageResult<NovelSummary> search(@RequestParam(value = "q", required = false) String keyword,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "12") int size) {
        if (keyword == null || keyword.isBlank()) {
            return PageResult.empty(page, size);
        }
        return PageResult.of(novelService.search(keyword.trim(), page, size), NovelSummary::of);
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
