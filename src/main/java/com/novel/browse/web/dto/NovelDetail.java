package com.novel.browse.web.dto;

import com.novel.browse.domain.Novel;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * Book detail: summary plus the first/last chapter and part of the table of contents.
 */
public record NovelDetail(NovelSummary novel, ChapterItem firstChapter, ChapterItem lastChapter,
                          List<ChapterItem> chapters, List<NovelSummary> related) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public static NovelDetail of(Novel novel, NovelSummary summary, List<ChapterItem> chapters,
                                 List<NovelSummary> related) {
        ChapterItem first = chapters.isEmpty() ? null : chapters.get(0);
        ChapterItem last = chapters.isEmpty() ? null : chapters.get(chapters.size() - 1);
        return new NovelDetail(summary, first, last, List.copyOf(chapters), List.copyOf(related));
    }

    public static NovelDetail empty(Novel novel, NovelSummary summary, List<NovelSummary> related) {
        return new NovelDetail(summary, null, null, List.of(), List.copyOf(related));
    }
}
