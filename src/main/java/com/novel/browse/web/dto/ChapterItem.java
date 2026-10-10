package com.novel.browse.web.dto;

import com.novel.browse.domain.Chapter;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 目录中的章节条目（不含正文）。
 */
public record ChapterItem(Long id, Integer no, String title, Integer wordCount,
                          LocalDateTime createdAt) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public static ChapterItem of(Chapter chapter) {
        return new ChapterItem(chapter.getId(), chapter.getChapterNo(), chapter.getTitle(),
                chapter.getWordCount(), chapter.getCreatedAt());
    }
}
