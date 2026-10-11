package com.novel.browse.web.dto;

import com.novel.browse.domain.ReadingProgress;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Where a reader stopped in a book
 */
public record ProgressVo(Long novelId, String novelTitle, Integer chapterNo, Integer chapterCount,
                         LocalDateTime updatedAt) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public static ProgressVo of(ReadingProgress progress) {
        var novel = progress.getNovel();
        return new ProgressVo(novel.getId(), novel.getTitle(), progress.getChapterNo(),
                novel.getChapterCount(), progress.getUpdatedAt());
    }
}
