package com.novel.browse.repository;

import com.novel.browse.domain.UserFavorite;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserFavoriteRepository extends JpaRepository<UserFavorite, Long> {

    Optional<UserFavorite> findByUserIdAndNovelId(Long userId, Long novelId);

    boolean existsByUserIdAndNovelId(Long userId, Long novelId);

    Page<UserFavorite> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    long deleteByUserIdAndNovelId(Long userId, Long novelId);
}
