package com.novel.browse.web;

import com.novel.browse.domain.Chapter;
import com.novel.browse.domain.Novel;
import com.novel.browse.service.ChapterService;
import com.novel.browse.service.NovelService;
import com.novel.browse.web.dto.ChapterDetail;
import com.novel.browse.web.dto.ChapterItem;
import com.novel.browse.web.dto.NovelDetail;
import com.novel.browse.web.dto.NovelSummary;
import com.novel.browse.web.dto.PageResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

/**
 * 书籍详情、目录与章节阅读（REST 接口）。
 */
@RestController
@RequestMapping("/novels")
public class BookController {

    private final NovelService novelService;
    private final ChapterService chapterService;

    public BookController(NovelService novelService, ChapterService chapterService) {
        this.novelService = novelService;
        this.chapterService = chapterService;
    }

    /** 书籍详情 + 最新章节 + 同类推荐 */
    @GetMapping("/{id}")
    public NovelDetail detail(@PathVariable Long id,
                              @RequestParam(defaultValue = "10") int chapterPreview) {
        Novel novel = novelService.viewDetail(id);
        List<Chapter> chapters = chapterService.listByNovel(id);
        List<ChapterItem> preview = chapters.stream()
                .limit(Math.max(chapterPreview, 0))
                .map(ChapterItem::of)
                .toList();
        List<NovelSummary> related = novelService.relatedList(novel, 6).stream()
                .map(NovelSummary::of)
                .toList();
        return NovelDetail.of(novel, NovelSummary.of(novel), preview, related);
    }

    /** 全部章节目录 */
    @GetMapping("/{id}/chapters")
    public List<ChapterItem> catalog(@PathVariable Long id) {
        Novel novel = novelService.detail(id);
        return chapterService.listByNovel(novel.getId()).stream().map(ChapterItem::of).toList();
    }

    /** 分页章节目录 */
    @GetMapping("/{id}/chapter-page")
    public PageResult<ChapterItem> catalogPaged(@PathVariable Long id,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "50") int size) {
        List<ChapterItem> all = catalog(id);
        int from = Math.min(Math.max(page, 0) * size, all.size());
        int to = Math.min(from + size, all.size());
        List<ChapterItem> content = all.subList(from, to);
        int totalPages = size <= 0 ? 0 : (int) Math.ceil((double) all.size() / size);
        return new PageResult<>(content, Math.max(page, 0), size, all.size(), totalPages,
                page <= 0, to >= all.size());
    }

    /** 按章节号阅读 */
    @GetMapping("/{id}/chapters/{no}")
    public ChapterDetail readByNo(@PathVariable Long id, @PathVariable Integer no) {
        return toDetail(chapterService.getByNo(id, no));
    }

    /** 按章节 id 阅读 */
    @GetMapping("/chapters/{chapterId}")
    public ChapterDetail readById(@PathVariable Long chapterId) {
        return toDetail(chapterService.getById(chapterId));
    }

    private ChapterDetail toDetail(Chapter chapter) {
        Long novelId = chapter.getNovel().getId();
        int no = chapter.getChapterNo();
        Integer prevNo = chapterService.previous(novelId, no).map(Chapter::getChapterNo).orElse(null);
        Integer nextNo = chapterService.next(novelId, no).map(Chapter::getChapterNo).orElse(null);
        return ChapterDetail.of(chapter, split(chapter.getContent()), prevNo, nextNo);
    }

    private List<String> split(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        return Arrays.stream(content.split("\n"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
