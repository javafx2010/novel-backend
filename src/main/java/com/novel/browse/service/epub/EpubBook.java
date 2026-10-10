package com.novel.browse.service.epub;

import java.util.List;

/**
 * EPUB 解析结果：OPF 元数据 + 按 spine 顺序排列的章节。
 */
public record EpubBook(String title, String author, String description, String language,
                       List<String> subjects, List<EpubChapter> chapters) {

    /** 正文字数（按非空白字符计） */
    public long wordCount() {
        long total = 0L;
        for (EpubChapter chapter : chapters) {
            total += TextUtil.wordCount(chapter.text());
        }
        return total;
    }
}
