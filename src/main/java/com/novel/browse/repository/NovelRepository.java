package com.novel.browse.repository;

import com.novel.browse.domain.Category;
import com.novel.browse.domain.Novel;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface NovelRepository extends JpaRepository<Novel, Long>, JpaSpecificationExecutor<Novel> {

    List<Novel> findTop6ByCategoryAndIdNotOrderByViewCountDesc(Category category, Long novelId);

    long countByCategory(Category category);

    /** Whether this EPUB (by content hash) was already imported, used to deduplicate uploads and startup scans */
    boolean existsBySourceHash(String sourceHash);

    /**
     * Finds the edition with the same title, author and language (case-insensitive).
     * The content hash is not enough: the same book re-generated with different bytes has a different hash.
     * Another language of the same work is a separate edition, not an overwrite of this one.
     */
    Optional<Novel> findFirstByTitleIgnoreCaseAndAuthorIgnoreCaseAndLanguageIgnoreCase(
            String title, String author, String language);

    /**
     * Finds any edition of the same work (same title and author, any language), used to put a new
     * translation into the version group of the editions already present.
     */
    Optional<Novel> findFirstByTitleIgnoreCaseAndAuthorIgnoreCase(String title, String author);

    /** Every language version of a work, ordered by language */
    List<Novel> findByVersionGroupOrderByLanguage(String versionGroup);

    /** Editions of one language, newest update first */
    List<Novel> findAllByLanguageIgnoreCaseOrderByUpdatedAtDesc(String language);

    /** Books changed after the given time, newest first (client incremental sync) */
    List<Novel> findByUpdatedAtAfterOrderByUpdatedAtDesc(LocalDateTime since);
}
