package com.novel.browse.repository;

import com.novel.browse.domain.UserLike;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserLikeRepository extends JpaRepository<UserLike, Long> {

    boolean existsByUserIdAndNovelId(Long userId, Long novelId);

    Page<UserLike> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    long countByNovelId(Long novelId);

    long deleteByUserIdAndNovelId(Long userId, Long novelId);
}
