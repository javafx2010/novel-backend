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
import com.novel.browse.service.epub.TextUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 后台管理：EPUB 上传入库、书目删除与列表。
 * <p>
 * 写入时在同一事务内维护小说的章节数、字数与更新时间，并让受影响的缓存失效，
 * 保证前台首页 / 详情 / 章节读到的是最新数据。
 */
@Service
public class AdminService {

    private static final Logger log = LoggerFactory.getLogger(AdminService.class);

    /** 上传书籍默认封面配色（无图片资源，沿用渐变色 + 首字方案） */
    private static final String[] COVER_COLORS = {
            "linear-gradient(135deg,#6a8cff,#8f6cff)",
            "linear-gradient(135deg,#ff7a59,#ffb26b)",
            "linear-gradient(135deg,#3ec9a7,#57b7ff)",
            "linear-gradient(135deg,#ff6b9d,#c86bff)",
            "linear-gradient(135deg,#f5c451,#f78b5a)",
            "linear-gradient(135deg,#5b8def,#3ec9c1)",
    };

    private final EpubParser epubParser;
    private final NovelRepository novelRepository;
    private final ChapterRepository chapterRepository;
    private final CategoryRepository categoryRepository;

    public AdminService(EpubParser epubParser,
                        NovelRepository novelRepository,
                        ChapterRepository chapterRepository,
                        CategoryRepository categoryRepository) {
        this.epubParser = epubParser;
        this.novelRepository = novelRepository;
        this.chapterRepository = chapterRepository;
        this.categoryRepository = categoryRepository;
    }

    /**
     * 上传 EPUB 并入库。
     *
     * @param data       EPUB 字节内容
     * @param fileName   原始文件名（用于默认标题与错误信息）
     * @param categoryId 分类 id
     * @param status     连载状态（空则为 ONGOING）
     * @param tags       标签（空则取 EPUB 的 dc:subject）
     */
    @Transactional
    @CacheEvict(cacheNames = CacheNames.HOME_LIST, allEntries = true)
    public Novel importEpub(byte[] data, String fileName, Long categoryId, String status, String tags) {
        EpubBook book = epubParser.parse(data, fileName);
        Category category = categoryRepository.findById(categoryId)
                .orElseThrow(() -> new NotFoundException("分类不存在：id=" + categoryId));

        String title = defaultIfBlank(book.title(), defaultTitle(fileName));
        Novel novel = new Novel();
        novel.setTitle(TextUtil.truncate(title, 128));
        novel.setAuthor(TextUtil.truncate(defaultIfBlank(book.author(), "佚名"), 64));
        novel.setCategory(category);
        novel.setCoverColor(COVER_COLORS[Math.abs(title.hashCode()) % COVER_COLORS.length]);
        novel.setCoverMark(coverMark(title));
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
        log.info("EPUB 入库完成：id={} 标题「{}」 章节={} 字数={}", saved.getId(), saved.getTitle(), chapters.size(), words);
        return saved;
    }

    /** 后台书目列表：按更新时间倒序 */
    @Transactional(readOnly = true)
    public List<Novel> listNovels() {
        return novelRepository.findAll(Sort.by(Sort.Direction.DESC, "updatedAt"));
    }

    /** 删除书目及其章节，并让相关缓存整体失效 */
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
                .orElseThrow(() -> new NotFoundException("小说不存在：id=" + id));
        int removed = chapterRepository.deleteByNovelId(id);
        novelRepository.delete(novel);
        log.info("删除书目：id={} 标题「{}」 章节={}", id, novel.getTitle(), removed);
    }

    private String coverMark(String title) {
        for (char c : title.toCharArray()) {
            if (!Character.isWhitespace(c)) {
                return String.valueOf(c);
            }
        }
        return "书";
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
        return name.isBlank() ? "未命名" : name;
    }

    private String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
