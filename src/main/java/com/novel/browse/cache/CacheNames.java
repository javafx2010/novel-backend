package com.novel.browse.cache;

/**
 * 缓存名称常量。
 */
public final class CacheNames {

    /** 首页聚合列表（热门、推荐、最近更新） */
    public static final String HOME_LIST = "novel-home-list";
    /** 小说详情 */
    public static final String NOVEL_DETAIL = "novel-detail";
    /** 章节正文（按章节 id） */
    public static final String CHAPTER = "novel-chapter";
    /** 章节正文（按小说 id + 章节号） */
    public static final String CHAPTER_NO = "novel-chapter-no";
    /** 小说章节列表 */
    public static final String CHAPTER_LIST = "novel-chapter-list";

    private CacheNames() {
    }
}
