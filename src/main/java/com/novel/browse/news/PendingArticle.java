package com.novel.browse.news;

/**
 * One article accepted for publishing: translated title and body plus the attribution data.
 *
 * @param title       chapter title (translated when the translator succeeded)
 * @param url         absolute source URL, the chapter deduplication key
 * @param sourceName  display name of the news source
 * @param publishedAt publish time from the feed, null when unknown
 * @param body        chapter content, paragraphs separated by {@code \n}; carries the attribution
 *                    header (source, link) and the original English title line
 */
public record PendingArticle(String title, String url, String sourceName,
                             java.time.LocalDateTime publishedAt, String body) {
}
