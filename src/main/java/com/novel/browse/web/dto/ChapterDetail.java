package com.novel.browse.web.dto;

import com.novel.browse.domain.Chapter;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 章节正文阅读视图。
 */
public record ChapterDetail(Long id, Integer no, String title, Integer wordCount,
                            List<String> paragraphs, Long novelId, String novelTitle,
                            Integer prevNo, Integer nextNo) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public static ChapterDetail of(Chapter chapter, List<String> paragraphs, Integer prevNo, Integer nextNo) {
        return new ChapterDetail(
                chapter.getId(),
                chapter.getChapterNo(),
                chapter.getTitle(),
                chapter.getWordCount(),
                List.copyOf(paragraphs),
                chapter.getNovel().getId(),
                chapter.getNovel().getTitle(),
                prevNo,
                nextNo);
    }
}
