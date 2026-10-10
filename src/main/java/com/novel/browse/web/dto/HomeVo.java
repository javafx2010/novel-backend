package com.novel.browse.web.dto;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 首页聚合数据。
 */
public record HomeVo(long totalNovels, List<CategoryDto> categories,
                     List<NovelSummary> recommends, List<NovelSummary> hot,
                     List<NovelSummary> finished, List<NovelUpdate> latest) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 最近更新书目及其最新章节标题 */
    public record NovelUpdate(NovelSummary novel, ChapterItem latestChapter) implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;
    }
}
