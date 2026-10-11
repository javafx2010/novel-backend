package com.novel.browse.service;

import com.novel.browse.domain.Novel;
import com.novel.browse.domain.ReadingProgress;
import com.novel.browse.domain.User;
import com.novel.browse.repository.NovelRepository;
import com.novel.browse.repository.ReadingProgressRepository;
import com.novel.browse.web.dto.PageResult;
import com.novel.browse.web.dto.ProgressVo;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Reading progress per reader and book: which chapter the reader stopped at
 */
@Service
public class ProgressService {

    private final ReadingProgressRepository progressRepository;
    private final NovelRepository novelRepository;

    public ProgressService(ReadingProgressRepository progressRepository, NovelRepository novelRepository) {
        this.progressRepository = progressRepository;
        this.novelRepository = novelRepository;
    }

    /**
     * Saves (or updates) the chapter a reader is on
     */
    @Transactional
    public ProgressVo save(User user, Long novelId, Integer chapterNo) {
        Novel novel = novelRepository.findById(novelId)
                .orElseThrow(() -> new NotFoundException("error.novel.notFoundId", novelId));
        if (chapterNo == null || chapterNo < 1) {
            throw new BadRequestException("error.progress.chapterNoMin");
        }
        if (novel.getChapterCount() != null && novel.getChapterCount() > 0 && chapterNo > novel.getChapterCount()) {
            throw new BadRequestException("error.progress.chapterNoExceeds", novel.getChapterCount());
        }
        ReadingProgress progress = progressRepository.findByUserIdAndNovelId(user.getId(), novelId)
                .orElseGet(ReadingProgress::new);
        LocalDateTime now = LocalDateTime.now();
        if (progress.getId() == null) {
            progress.setUser(user);
            progress.setNovel(novel);
            progress.setCreatedAt(now);
        }
        progress.setChapterNo(chapterNo);
        progress.setUpdatedAt(now);
        return ProgressVo.of(progressRepository.save(progress));
    }

    @Transactional(readOnly = true)
    public ProgressVo get(User user, Long novelId) {
        return progressRepository.findByUserIdAndNovelId(user.getId(), novelId)
                .map(ProgressVo::of)
                .orElse(null);
    }

    @Transactional
    public void remove(User user, Long novelId) {
        progressRepository.deleteByUserIdAndNovelId(user.getId(), novelId);
    }

    @Transactional(readOnly = true)
    public PageResult<ProgressVo> list(User user, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(0, page), size <= 0 ? 20 : Math.min(size, 100));
        Page<ReadingProgress> progresses = progressRepository.findByUserIdOrderByUpdatedAtDesc(user.getId(), pageable);
        return PageResult.of(progresses, ProgressVo::of);
    }
}
