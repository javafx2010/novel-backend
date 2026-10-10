package com.novel.browse.service.epub;

/**
 * One chapter parsed from an EPUB: title and plain text content.
 */
public record EpubChapter(String title, String text) {
}
