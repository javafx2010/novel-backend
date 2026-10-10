package com.novel.browse.service.epub;

import java.util.List;

/**
 * EPUB parse result: OPF metadata plus chapters ordered by the spine.
 *
 * @param coverImage     raw bytes of the embedded cover image, null when the EPUB has none
 * @param coverMediaType MIME type of the cover image, null when there is no cover
 */
public record EpubBook(String title, String author, String description, String language,
                       List<String> subjects, List<EpubChapter> chapters,
                       byte[] coverImage, String coverMediaType) {

    /** Content word count (non-whitespace characters) */
    public long wordCount() {
        long total = 0L;
        for (EpubChapter chapter : chapters) {
            total += TextUtil.wordCount(chapter.text());
        }
        return total;
    }
}
