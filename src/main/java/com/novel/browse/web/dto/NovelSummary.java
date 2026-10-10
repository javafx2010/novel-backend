package com.novel.browse.web.dto;

import com.novel.browse.domain.Novel;

import java.io.Serializable;
import java.io.Serial;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 列表场景下的书籍摘要。
 */
public record NovelSummary(Long id, String title, String author, String description,
                           Long categoryId, String categoryName, String coverColor, String coverMark,
                           String status, String statusText, List<String> tags,
                           Double rating, Long viewCount, Long wordCount, Integer chapterCount,
                           LocalDateTime updatedAt) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public static NovelSummary of(Novel novel) {
        return new NovelSummary(
                novel.getId(),
                novel.getTitle(),
                novel.getAuthor(),
                novel.getDescription(),
                novel.getCategory() == null ? null : novel.getCategory().getId(),
                novel.getCategory() == null ? null : novel.getCategory().getName(),
                novel.getCoverColor(),
                novel.getCoverMark(),
                novel.getStatus(),
                novel.getStatusText(),
                List.of(novel.getTagList()),
                novel.getRating(),
                novel.getViewCount(),
                novel.getWordCount(),
                novel.getChapterCount(),
                novel.getUpdatedAt());
    }
}
