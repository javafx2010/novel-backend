package com.novel.browse.service;

import com.novel.browse.domain.Novel;
import com.novel.browse.domain.User;
import com.novel.browse.domain.UserFavorite;
import com.novel.browse.repository.NovelRepository;
import com.novel.browse.repository.UserFavoriteRepository;
import com.novel.browse.web.dto.NovelSummary;
import com.novel.browse.web.dto.PageResult;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reader bookshelf: add a book to the favorites, remove it and list it back
 */
@Service
public class FavoriteService {

    private final UserFavoriteRepository favoriteRepository;
    private final NovelRepository novelRepository;

    public FavoriteService(UserFavoriteRepository favoriteRepository, NovelRepository novelRepository) {
        this.favoriteRepository = favoriteRepository;
        this.novelRepository = novelRepository;
    }

    /**
     * Adds a book to the shelf; adding twice is a no-op so the call is idempotent
     */
    @Transactional
    public void add(User user, Long novelId) {
        Novel novel = novelRepository.findById(novelId)
                .orElseThrow(() -> new NotFoundException("Novel not found: " + novelId));
        if (favoriteRepository.existsByUserIdAndNovelId(user.getId(), novelId)) {
            return;
        }
        UserFavorite favorite = new UserFavorite();
        favorite.setUser(user);
        favorite.setNovel(novel);
        favoriteRepository.save(favorite);
    }

    @Transactional
    public void remove(User user, Long novelId) {
        favoriteRepository.deleteByUserIdAndNovelId(user.getId(), novelId);
    }

    public boolean contains(User user, Long novelId) {
        return favoriteRepository.existsByUserIdAndNovelId(user.getId(), novelId);
    }

    @Transactional(readOnly = true)
    public PageResult<NovelSummary> list(User user, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(0, page), size <= 0 ? 20 : Math.min(size, 100));
        Page<UserFavorite> favorites = favoriteRepository.findByUserIdOrderByCreatedAtDesc(user.getId(), pageable);
        return PageResult.of(favorites, favorite -> NovelSummary.of(favorite.getNovel()));
    }
}
