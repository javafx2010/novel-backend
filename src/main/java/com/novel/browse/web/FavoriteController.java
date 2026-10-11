package com.novel.browse.web;

import com.novel.browse.domain.User;
import com.novel.browse.service.FavoriteService;
import com.novel.browse.web.dto.FavoriteStatus;
import com.novel.browse.web.dto.NovelSummary;
import com.novel.browse.web.dto.PageResult;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Bookshelf of the logged in reader
 */
@RestController
@RequestMapping("/me/favorites")
public class FavoriteController {

    private final FavoriteService favoriteService;

    public FavoriteController(FavoriteService favoriteService) {
        this.favoriteService = favoriteService;
    }

    @GetMapping
    public PageResult<NovelSummary> list(@CurrentUser User user,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        return favoriteService.list(user, page, size);
    }

    @GetMapping("/{novelId}")
    public FavoriteStatus status(@CurrentUser User user, @PathVariable Long novelId) {
        return new FavoriteStatus(novelId, favoriteService.contains(user, novelId));
    }

    @PostMapping("/{novelId}")
    public FavoriteStatus add(@CurrentUser User user, @PathVariable Long novelId) {
        favoriteService.add(user, novelId);
        return new FavoriteStatus(novelId, true);
    }

    @DeleteMapping("/{novelId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@CurrentUser User user, @PathVariable Long novelId) {
        favoriteService.remove(user, novelId);
    }
}
