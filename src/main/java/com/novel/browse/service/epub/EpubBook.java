package com.novel.browse.service.epub;

import java.util.List;

/**
 * EPUB parse result: OPF metadata plus chapters ordered by the spine.
 */
public record EpubBook(String title, String author, String description, String language,
                       List<String> subjects, List<EpubChapter> chapters) {

    /** Content word count (non-whitespace characters) */
    public long wordCount() {
        long total = 0L;
        for (EpubChapter chapter : chapters) {
            total += TextUtil.wordCount(chapter.text());
        }
        return total;
    }
}
