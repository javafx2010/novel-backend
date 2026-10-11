package com.novel.browse.web.dto;

/**
 * Whether a book is on the reader's bookshelf
 */
public record FavoriteStatus(Long novelId, boolean favorited) {
}
