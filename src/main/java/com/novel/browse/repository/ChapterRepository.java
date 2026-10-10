package com.novel.browse.repository;

import com.novel.browse.domain.Chapter;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ChapterRepository extends JpaRepository<Chapter, Long> {

    List<Chapter> findByNovelIdOrderByChapterNoAsc(Long novelId);

    Optional<Chapter> findByNovelIdAndChapterNo(Long novelId, Integer chapterNo);

    Optional<Chapter> findFirstByNovelIdOrderByChapterNoAsc(Long novelId);

    Optional<Chapter> findFirstByNovelIdAndChapterNoGreaterThanOrderByChapterNoAsc(Long novelId, Integer chapterNo);

    Optional<Chapter> findFirstByNovelIdAndChapterNoLessThanOrderByChapterNoDesc(Long novelId, Integer chapterNo);

    List<Chapter> findTopByNovelIdOrderByChapterNoDesc(Long novelId, Pageable pageable);

    /** 删除某本书的全部章节，返回删除条数 */
    int deleteByNovelId(Long novelId);
}
