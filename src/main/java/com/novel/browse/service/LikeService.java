package com.novel.browse.service;

import com.novel.browse.cache.CacheNames;
import com.novel.browse.domain.Novel;
import com.novel.browse.domain.User;
import com.novel.browse.domain.UserLike;
import com.novel.browse.repository.NovelRepository;
import com.novel.browse.repository.UserLikeRepository;
import com.novel.browse.web.dto.LikeStatus;
import com.novel.browse.web.dto.NovelSummary;
import com.novel.browse.web.dto.PageResult;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Likes on books: browsing the library is open to everyone, liking is not - every method here needs a
 * logged in reader, which the controller enforces before calling in.
 */
@Service
public class LikeService {

    private final UserLikeRepository likeRepository;
    private final NovelRepository novelRepository;

    public LikeService(UserLikeRepository likeRepository, NovelRepository novelRepository) {
        this.likeRepository = likeRepository;
        this.novelRepository = novelRepository;
    }

    /**
     * Likes a book; liking twice is a no-op so the count never grows twice for one reader
     */
    @Transactional
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheNames.HOME_LIST, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.NOVEL_DETAIL, allEntries = true)
    })
    public LikeStatus like(User user, Long novelId) {
        Novel novel = novelRepository.findById(novelId)
                .orElseThrow(() -> new NotFoundException("error.novel.notFoundId", novelId));
        if (!likeRepository.existsByUserIdAndNovelId(user.getId(), novelId)) {
            UserLike like = new UserLike();
            like.setUser(user);
            like.setNovel(novel);
            likeRepository.save(like);
            refreshCount(novel);
        }
        return new LikeStatus(novelId, true, novel.getLikeCount());
    }

    /**
     * Removes the reader's like
     */
    @Transactional
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheNames.HOME_LIST, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.NOVEL_DETAIL, allEntries = true)
    })
    public LikeStatus unlike(User user, Long novelId) {
        if (likeRepository.deleteByUserIdAndNovelId(user.getId(), novelId) > 0) {
            novelRepository.findById(novelId).ifPresent(this::refreshCount);
        }
        Novel novel = novelRepository.findById(novelId)
                .orElseThrow(() -> new NotFoundException("error.novel.notFoundId", novelId));
        return new LikeStatus(novelId, false, novel.getLikeCount());
    }

    @Transactional(readOnly = true)
    public LikeStatus status(User user, Long novelId) {
        Novel novel = novelRepository.findById(novelId)
                .orElseThrow(() -> new NotFoundException("error.novel.notFoundId", novelId));
        return new LikeStatus(novelId, likeRepository.existsByUserIdAndNovelId(user.getId(), novelId),
                novel.getLikeCount());
    }

    /**
     * Books the reader liked, newest first
     */
    @Transactional(readOnly = true)
    public PageResult<NovelSummary> list(User user, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(0, page), size <= 0 ? 20 : Math.min(size, 100));
        Page<UserLike> likes = likeRepository.findByUserIdOrderByCreatedAtDesc(user.getId(), pageable);
        return PageResult.of(likes, like -> NovelSummary.of(like.getNovel()));
    }

    private void refreshCount(Novel novel) {
        novel.setLikeCount((int) likeRepository.countByNovelId(novel.getId()));
        novel.setUpdatedAt(LocalDateTime.now());
        novelRepository.save(novel);
    }
}
