package com.novel.browse.repository;

import com.novel.browse.domain.Review;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ReviewRepository extends JpaRepository<Review, Long> {

    Optional<Review> findByUserIdAndNovelId(Long userId, Long novelId);

    Page<Review> findByNovelIdOrderByCreatedAtDesc(Long novelId, Pageable pageable);

    long countByNovelId(Long novelId);

    /**
     * Average star rating of a book, null when nobody rated it yet
     */
    @Query("select avg(r.rating) from Review r where r.novel.id = :novelId")
    Double averageRating(@Param("novelId") Long novelId);

    long deleteByUserIdAndNovelId(Long userId, Long novelId);
}
