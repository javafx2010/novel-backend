package com.novel.browse.cache;

/**
 * Cache name constants.
 */
public final class CacheNames {

    /** Home page aggregates (hot, featured, latest) */
    public static final String HOME_LIST = "novel-home-list";
    /** Novel detail */
    public static final String NOVEL_DETAIL = "novel-detail";
    /** Chapter content (by chapter id) */
    public static final String CHAPTER = "novel-chapter";
    /** Chapter content (by novel id + chapter number) */
    public static final String CHAPTER_NO = "novel-chapter-no";
    /** Novel chapter list */
    public static final String CHAPTER_LIST = "novel-chapter-list";
    /** API signature nonce (replay protection) */
    public static final String SIGNATURE_NONCE = "signature-nonce";

    private CacheNames() {
    }
}
