package com.novel.browse.web.dto;

import com.novel.browse.domain.ReadingProgress;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Where a reader stopped in a book: the chapter, how much of it, and the resulting percentage of the whole book
 */
public record ProgressVo(Long novelId, String novelTitle, Integer chapterNo, Integer chapterCount,
                         Integer chapterPercent, Integer percent, LocalDateTime updatedAt)
        implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public static ProgressVo of(ReadingProgress progress) {
        var novel = progress.getNovel();
        return new ProgressVo(novel.getId(), novel.getTitle(), progress.getChapterNo(), novel.getChapterCount(),
                progress.getChapterPercent(),
                percentOf(progress.getChapterNo(), progress.getChapterPercent(), novel.getChapterCount()),
                progress.getUpdatedAt());
    }

    /**
     * Percentage of the whole book: the chapters already finished plus the part of the chapter being read
     * ({@code ((chapterNo - 1) + chapterPercent / 100) / chapterCount}, rounded).
     *
     * @return 0-100, or {@code null} when the book has no chapter count yet
     */
    private static Integer percentOf(Integer chapterNo, Integer chapterPercent, Integer chapterCount) {
        if (chapterCount == null || chapterCount <= 0) {
            return null;
        }
        double read = Math.max(0, (chapterNo == null ? 1 : chapterNo) - 1)
                + Math.max(0, Math.min(100, chapterPercent == null ? 0 : chapterPercent)) / 100.0;
        long percent = Math.round(read / chapterCount * 100);
        return (int) Math.max(0, Math.min(100, percent));
    }
}
