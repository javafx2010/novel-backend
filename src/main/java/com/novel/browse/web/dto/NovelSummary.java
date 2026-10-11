package com.novel.browse.web.dto;

import com.novel.browse.domain.Novel;
import com.novel.browse.service.CoverStorage;

import java.io.Serializable;
import java.io.Serial;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Book summary used in list views.
 */
public record NovelSummary(Long id, String title, String author, String description,
                           Long categoryId, String categoryName, String coverColor, String coverMark,
                           String coverUrl, String status, String statusText, List<String> tags,
                           Double rating, Integer ratingCount, Integer likeCount, Long viewCount, Long wordCount,
                           Integer chapterCount, LocalDateTime updatedAt) implements Serializable {

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
                CoverStorage.url(novel.getCoverImage()),
                novel.getStatus(),
                novel.getStatusText(),
                List.of(novel.getTagList()),
                novel.getRating(),
                novel.getRatingCount(),
                novel.getLikeCount(),
                novel.getViewCount(),
                novel.getWordCount(),
                novel.getChapterCount(),
                novel.getUpdatedAt());
    }
}
