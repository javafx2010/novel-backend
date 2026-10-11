package com.novel.browse.web;

import com.novel.browse.domain.User;
import com.novel.browse.service.NotFoundException;
import com.novel.browse.service.ReviewService;
import com.novel.browse.web.dto.PageResult;
import com.novel.browse.web.dto.ReviewVo;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ratings and comments: reading is public, writing belongs to the logged in reader
 */
@RestController
public class ReviewController {

    private final ReviewService reviewService;

    public ReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @GetMapping("/novels/{novelId}/reviews")
    public PageResult<ReviewVo> listByNovel(@PathVariable Long novelId,
                                            @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "20") int size) {
        return reviewService.listByNovel(novelId, page, size);
    }

    @GetMapping("/me/reviews/{novelId}")
    public ReviewVo mine(@CurrentUser User user, @PathVariable Long novelId) {
        ReviewVo review = reviewService.getMine(user, novelId);
        if (review == null) {
            throw new NotFoundException("No review for novel: " + novelId);
        }
        return review;
    }

    @PutMapping("/me/reviews/{novelId}")
    public ReviewVo save(@CurrentUser User user, @PathVariable Long novelId, @RequestBody ReviewRequest body) {
        if (body == null || body.rating() == null) {
            throw new IllegalArgumentException("rating is required");
        }
        return reviewService.save(user, novelId, body.rating(), body.content());
    }

    @DeleteMapping("/me/reviews/{novelId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@CurrentUser User user, @PathVariable Long novelId) {
        reviewService.remove(user, novelId);
    }

    /**
     * Create or update request: star rating 1-5 and an optional comment
     */
    public record ReviewRequest(Integer rating, String content) {
    }
}
