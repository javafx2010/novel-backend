package com.novel.browse.service.epub;

/**
 * EPUB 解析出的一个章节：标题与纯文本正文。
 */
public record EpubChapter(String title, String text) {
}
