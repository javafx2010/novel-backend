package com.novel.browse.web.dto;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * Home page aggregates.
 */
public record HomeVo(long totalNovels, List<CategoryDto> categories,
                     List<NovelSummary> recommends, List<NovelSummary> hot,
                     List<NovelSummary> finished, List<NovelUpdate> latest) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** Recently updated novel with its latest chapter title */
    public record NovelUpdate(NovelSummary novel, ChapterItem latestChapter) implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;
    }
}
