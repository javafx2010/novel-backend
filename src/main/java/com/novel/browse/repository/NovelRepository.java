package com.novel.browse.repository;

import com.novel.browse.domain.Category;
import com.novel.browse.domain.Novel;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface NovelRepository extends JpaRepository<Novel, Long> {

    Page<Novel> findByCategoryOrderByViewCountDesc(Category category, Pageable pageable);

    Page<Novel> findByCategoryOrderByUpdatedAtDesc(Category category, Pageable pageable);

    Page<Novel> findByStatusOrderByViewCountDesc(String status, Pageable pageable);

    @Query("select n from Novel n where lower(n.title) like lower(concat('%', :keyword, '%')) "
            + "or lower(n.author) like lower(concat('%', :keyword, '%')) "
            + "or lower(n.tags) like lower(concat('%', :keyword, '%'))")
    Page<Novel> search(@Param("keyword") String keyword, Pageable pageable);

    List<Novel> findTop10ByOrderByViewCountDesc();

    List<Novel> findTop8ByOrderByRatingDesc();

    List<Novel> findTop8ByOrderByUpdatedAtDesc();

    List<Novel> findTop6ByCategoryAndIdNotOrderByViewCountDesc(Category category, Long novelId);

    long countByCategory(Category category);
}
