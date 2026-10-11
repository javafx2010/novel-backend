package com.novel.browse.web.dto;

/**
 * Whether the reader liked a book, and how many likes the book has in total
 */
public record LikeStatus(Long novelId, boolean liked, Integer likeCount) {
}
