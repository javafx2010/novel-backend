package com.novel.browse.web;

import com.novel.browse.domain.User;
import com.novel.browse.service.LikeService;
import com.novel.browse.web.dto.LikeStatus;
import com.novel.browse.web.dto.NovelSummary;
import com.novel.browse.web.dto.PageResult;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Likes of the logged in reader. Reading the library needs no account, liking does: every call here
 * answers 401 without a valid session cookie.
 */
@RestController
@RequestMapping("/me/likes")
public class LikeController {

    private final LikeService likeService;

    public LikeController(LikeService likeService) {
        this.likeService = likeService;
    }

    @GetMapping
    public PageResult<NovelSummary> list(@CurrentUser User user,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        return likeService.list(user, page, size);
    }

    @GetMapping("/{novelId}")
    public LikeStatus status(@CurrentUser User user, @PathVariable Long novelId) {
        return likeService.status(user, novelId);
    }

    @PostMapping("/{novelId}")
    public LikeStatus like(@CurrentUser User user, @PathVariable Long novelId) {
        return likeService.like(user, novelId);
    }

    @DeleteMapping("/{novelId}")
    public LikeStatus unlike(@CurrentUser User user, @PathVariable Long novelId) {
        return likeService.unlike(user, novelId);
    }
}
