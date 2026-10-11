package com.novel.browse.web.dto;

import com.novel.browse.domain.Review;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * A reader's rating and comment on a book
 */
public record ReviewVo(Long id, Long novelId, Long userId, String username, Integer rating, String content,
                       LocalDateTime createdAt, LocalDateTime updatedAt) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public static ReviewVo of(Review review) {
        return new ReviewVo(review.getId(), review.getNovel().getId(), review.getUser().getId(),
                review.getUser().getUsername(), review.getRating(), review.getContent(),
                review.getCreatedAt(), review.getUpdatedAt());
    }
}
