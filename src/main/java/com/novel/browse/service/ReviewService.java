package com.novel.browse.service;

import com.novel.browse.cache.CacheNames;
import com.novel.browse.domain.Novel;
import com.novel.browse.domain.Review;
import com.novel.browse.domain.User;
import com.novel.browse.repository.NovelRepository;
import com.novel.browse.repository.ReviewRepository;
import com.novel.browse.web.dto.PageResult;
import com.novel.browse.web.dto.ReviewVo;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

/**
 * Reader reviews: a star rating plus an optional comment, one per reader and book.
 * Saving or deleting a review refreshes {@code nb_novel.rating} (0-10 with one decimal, the average star
 * rating doubled) and {@code nb_novel.rating_count}.
 */
@Service
public class ReviewService {

    private final ReviewRepository reviewRepository;
    private final NovelRepository novelRepository;

    public ReviewService(ReviewRepository reviewRepository, NovelRepository novelRepository) {
        this.reviewRepository = reviewRepository;
        this.novelRepository = novelRepository;
    }

    /**
     * Creates or replaces the reader's review of a book
     */
    @Transactional
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheNames.HOME_LIST, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.NOVEL_DETAIL, allEntries = true)
    })
    public ReviewVo save(User user, Long novelId, Integer rating, String content) {
        Novel novel = novelRepository.findById(novelId)
                .orElseThrow(() -> new NotFoundException("Novel not found: " + novelId));
        if (rating == null || rating < Review.MIN_RATING || rating > Review.MAX_RATING) {
            throw new BadRequestException("rating must be between " + Review.MIN_RATING + " and " + Review.MAX_RATING);
        }
        String text = StringUtils.hasText(content) ? content.trim() : null;
        if (text != null && text.length() > 1000) {
            throw new BadRequestException("Comment must not exceed 1000 characters");
        }
        Review review = reviewRepository.findByUserIdAndNovelId(user.getId(), novelId).orElseGet(Review::new);
        LocalDateTime now = LocalDateTime.now();
        if (review.getId() == null) {
            review.setUser(user);
            review.setNovel(novel);
            review.setCreatedAt(now);
        }
        review.setRating(rating);
        review.setContent(text);
        review.setUpdatedAt(now);
        Review saved = reviewRepository.save(review);
        refreshRating(novel);
        return ReviewVo.of(saved);
    }

    @Transactional
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheNames.HOME_LIST, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.NOVEL_DETAIL, allEntries = true)
    })
    public void remove(User user, Long novelId) {
        if (reviewRepository.deleteByUserIdAndNovelId(user.getId(), novelId) == 0) {
            return;
        }
        novelRepository.findById(novelId).ifPresent(this::refreshRating);
    }

    @Transactional(readOnly = true)
    public ReviewVo getMine(User user, Long novelId) {
        return reviewRepository.findByUserIdAndNovelId(user.getId(), novelId)
                .map(ReviewVo::of)
                .orElse(null);
    }

    /**
     * Reviews of a book, newest first
     */
    @Transactional(readOnly = true)
    public PageResult<ReviewVo> listByNovel(Long novelId, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(0, page), size <= 0 ? 20 : Math.min(size, 100));
        Page<Review> reviews = reviewRepository.findByNovelIdOrderByCreatedAtDesc(novelId, pageable);
        return PageResult.of(reviews, ReviewVo::of);
    }

    /**
     * Recomputes the average rating and the number of ratings of a book
     */
    private void refreshRating(Novel novel) {
        long count = reviewRepository.countByNovelId(novel.getId());
        Double average = reviewRepository.averageRating(novel.getId());
        novel.setRatingCount((int) count);
        // nb_novel.rating is a 0-10 score with one decimal, the star average (1-5) is doubled
        novel.setRating(average == null || count == 0 ? 0.0 : Math.round(average * 2.0 * 10.0) / 10.0);
        novel.setUpdatedAt(LocalDateTime.now());
        novelRepository.save(novel);
    }
}
