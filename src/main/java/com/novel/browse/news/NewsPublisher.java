package com.novel.browse.news;

import com.novel.browse.cache.CacheNames;
import com.novel.browse.domain.Chapter;
import com.novel.browse.domain.Novel;
import com.novel.browse.repository.ChapterRepository;
import com.novel.browse.repository.NovelRepository;
import com.novel.browse.service.NovelService;
import com.novel.browse.service.epub.TextUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Writes crawled news into the library: one book per day
 * ({@code 《新闻日报 2026-11-15》}), one chapter per article, idempotent by source URL.
 */
@Service
public class NewsPublisher {

    private static final Logger log = LoggerFactory.getLogger(NewsPublisher.class);

    private final NewsProperties properties;
    private final NovelService novelService;
    private final NovelRepository novelRepository;
    private final ChapterRepository chapterRepository;

    public NewsPublisher(NewsProperties properties, NovelService novelService,
                         NovelRepository novelRepository, ChapterRepository chapterRepository) {
        this.properties = properties;
        this.novelService = novelService;
        this.novelRepository = novelRepository;
        this.chapterRepository = chapterRepository;
    }

    /**
     * Appends the articles to the daily book of {@code date}; creates the book and the news
     * category when missing. Articles already present (by source URL) are skipped.
     */
    @Transactional
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheNames.HOME_LIST, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.NOVEL_DETAIL, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.CHAPTER_LIST, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.CHAPTER, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.CHAPTER_NO, allEntries = true)
    })
    public Novel publish(LocalDate date, List<PendingArticle> articles) {
        String title = properties.getTitlePrefix() + " " + date;
        Novel novel = novelRepository
                .findFirstByTitleIgnoreCaseAndAuthorIgnoreCaseAndLanguageIgnoreCase(
                        title, properties.getAuthor(), properties.getLanguage())
                .orElseGet(() -> createBook(title));

        int nextNo = chapterRepository.findFirstByNovelIdOrderByChapterNoDesc(novel.getId())
                .map(chapter -> chapter.getChapterNo() + 1)
                .orElse(1);
        int added = 0;
        int skipped = 0;
        for (PendingArticle article : articles) {
            if (chapterRepository.existsByNovelIdAndSourceUrl(novel.getId(), article.url())) {
                skipped++;
                continue;
            }
            Chapter chapter = new Chapter();
            chapter.setNovel(novel);
            chapter.setChapterNo(nextNo++);
            chapter.setTitle(TextUtil.truncate(article.title(), 200));
            chapter.setContent(article.body());
            chapter.setWordCount((int) TextUtil.wordCount(article.body()));
            chapter.setSourceUrl(article.url());
            chapterRepository.save(chapter);
            added++;
        }

        if (added > 0) {
            refreshCounts(novel);
            novel.setUpdatedAt(LocalDateTime.now());
            novelRepository.save(novel);
            log.info("News published: novel={} chaptersAdded={} duplicatesSkipped={}", title, added, skipped);
        } else {
            log.info("News published: novel={} nothing new, all {} article(s) already stored", title, skipped);
        }
        return novel;
    }

    /**
     * Deletes the daily news books dated before {@code cutoff}, together with their chapters.
     * Only books matching the crawler's own title prefix and author are touched.
     *
     * @return how many books were deleted
     */
    @Transactional
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheNames.HOME_LIST, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.NOVEL_DETAIL, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.CHAPTER_LIST, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.CHAPTER, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.CHAPTER_NO, allEntries = true)
    })
    public int purgeOlderThan(LocalDate cutoff) {
        List<Novel> expired = novelRepository
                .findByAuthorIgnoreCaseAndTitleStartingWithIgnoreCase(
                        properties.getAuthor(), properties.getTitlePrefix())
                .stream()
                .filter(novel -> dateOf(novel.getTitle()).map(date -> date.isBefore(cutoff)).orElse(false))
                .toList();
        for (Novel novel : expired) {
            chapterRepository.deleteByNovelId(novel.getId());
            novelRepository.delete(novel);
        }
        if (!expired.isEmpty()) {
            log.info("Expired news books deleted: {} (cutoff {})", expired.size(), cutoff);
        }
        // The news category itself disappears once the purge removed its last book
        novelService.purgeEmptyCategories();
        return expired.size();
    }

    /** Parses the {@code yyyy-MM-dd} suffix of the daily title; empty for other titles */
    private Optional<LocalDate> dateOf(String title) {
        String suffix = title.substring(properties.getTitlePrefix().length()).trim();
        try {
            return Optional.of(LocalDate.parse(suffix));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** Creates the daily book together with the configured news category */
    private Novel createBook(String title) {
        Novel novel = new Novel();
        novel.setTitle(title);
        novel.setAuthor(properties.getAuthor());
        novel.setCategory(novelService.findOrCreateCategory(
                properties.getCategoryName(), properties.getLanguage(), properties.getCategoryDescription()));
        novel.setDescription(properties.getTitlePrefix() + "：自动抓取翻译的新闻，每天一本。");
        novel.setStatus(Novel.STATUS_ONGOING);
        novel.setLanguage(properties.getLanguage());
        novel.setCoverColor("#2196F3");
        novel.setCoverMark("报");
        novel.setChapterCount(0);
        novel.setWordCount(0L);
        return novelRepository.save(novel);
    }

    /** Recomputes chapter and word totals from the stored chapters */
    private void refreshCounts(Novel novel) {
        List<Chapter> chapters = chapterRepository.findByNovelIdOrderByChapterNoAsc(novel.getId());
        novel.setChapterCount(chapters.size());
        novel.setWordCount(chapters.stream().mapToLong(Chapter::getWordCount).sum());
    }
}
