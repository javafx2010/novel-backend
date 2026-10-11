package com.novel.browse.service;

import com.novel.browse.cache.CacheNames;
import com.novel.browse.domain.Chapter;
import com.novel.browse.repository.ChapterRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@Transactional(readOnly = true)
public class ChapterService {

    private final ChapterRepository chapterRepository;

    public ChapterService(ChapterRepository chapterRepository) {
        this.chapterRepository = chapterRepository;
    }

    @Cacheable(cacheNames = CacheNames.CHAPTER_LIST, key = "#novelId")
    public List<Chapter> listByNovel(Long novelId) {
        return chapterRepository.findByNovelIdOrderByChapterNoAsc(novelId);
    }

    public Optional<Chapter> firstChapter(Long novelId) {
        return chapterRepository.findFirstByNovelIdOrderByChapterNoAsc(novelId);
    }

    /** Latest chapter */
    public Optional<Chapter> lastChapter(Long novelId) {
        return chapterRepository.findTopByNovelIdOrderByChapterNoDesc(novelId, PageRequest.of(0, 1))
                .stream().findFirst();
    }

    @Cacheable(cacheNames = CacheNames.CHAPTER_NO, key = "#novelId + ':' + #chapterNo")
    public Chapter getByNo(Long novelId, Integer chapterNo) {
        return chapterRepository.findByNovelIdAndChapterNo(novelId, chapterNo)
                .orElseThrow(() -> new NotFoundException("error.chapter.notFoundNo", chapterNo));
    }

    @Cacheable(cacheNames = CacheNames.CHAPTER, key = "#id")
    public Chapter getById(Long id) {
        return chapterRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("error.chapter.notFoundId", id));
    }

    public Optional<Chapter> previous(Long novelId, Integer chapterNo) {
        return chapterRepository.findFirstByNovelIdAndChapterNoLessThanOrderByChapterNoDesc(novelId, chapterNo);
    }

    public Optional<Chapter> next(Long novelId, Integer chapterNo) {
        return chapterRepository.findFirstByNovelIdAndChapterNoGreaterThanOrderByChapterNoAsc(novelId, chapterNo);
    }
}
