package com.novel.browse.news;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * News crawler settings, prefix {@code app.news}.
 *
 * <p>Each run crawls the configured US news sources (English), translates every article into Chinese
 * and publishes one book per day, e.g. {@code 《新闻日报 2026-11-15》} with one chapter per article.
 */
@Component
@ConfigurationProperties(prefix = "app.news")
public class NewsProperties {

    /** Master switch; the scheduled run and the manual trigger both respect it */
    private boolean enabled = true;

    /** Crawl schedule (Spring cron), by default at the top of every hour */
    private String cron = "0 0 * * * *";

    /** Category the daily news books are published into (created when missing) */
    private String categoryName = "新闻";

    /** Description of the auto created category */
    private String categoryDescription = "自动抓取翻译的美国新闻";

    /** Language tag of the published book (the translated edition) */
    private String language = "zh_CN";

    /** Author shown for the daily books */
    private String author = "新闻抓取";

    /** First part of the daily book title; the date is appended */
    private String titlePrefix = "新闻日报";

    /** Upper bound of chapters imported from one source per run */
    private int maxItems = 20;

    /** How many days of daily news books to keep; older ones are deleted on every run */
    private int retentionDays = 30;

    /** Connect/read timeout per HTTP request, in seconds */
    private int timeoutSeconds = 20;

    /** User agent for crawler requests */
    private String userAgent = "Mozilla/5.0 (compatible; novel-browse-news/1.0)";

    /** Hard cap of chapter content characters; longer articles are truncated */
    private int maxArticleChars = 60_000;

    // --- translation ---

    /** Whether articles are translated before publishing; false keeps the original English text */
    private boolean translateEnabled = true;

    /** Source language sent to the translation service */
    private String translateFrom = "en";

    /** Target language sent to the translation service */
    private String translateTo = "zh-CN";

    /** Translation endpoint; defaults to the keyless Google Translate web endpoint */
    private String translateEndpoint =
            "https://translate.googleapis.com/translate_a/single?client=gtx&dt=t";

    /** Characters sent per translation request (the GET URL must stay short) */
    private int translateChunkChars = 1200;

    /** Pause between translation requests, in milliseconds, to stay polite */
    private int translateDelayMs = 300;

    /** News sources to crawl */
    private List<Source> sources = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getCron() {
        return cron;
    }

    public void setCron(String cron) {
        this.cron = cron;
    }

    public String getCategoryName() {
        return categoryName;
    }

    public void setCategoryName(String categoryName) {
        this.categoryName = categoryName;
    }

    public String getCategoryDescription() {
        return categoryDescription;
    }

    public void setCategoryDescription(String categoryDescription) {
        this.categoryDescription = categoryDescription;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author;
    }

    public String getTitlePrefix() {
        return titlePrefix;
    }

    public void setTitlePrefix(String titlePrefix) {
        this.titlePrefix = titlePrefix;
    }

    public int getMaxItems() {
        return maxItems;
    }

    public void setMaxItems(int maxItems) {
        this.maxItems = maxItems;
    }

    public int getRetentionDays() {
        return retentionDays;
    }

    public void setRetentionDays(int retentionDays) {
        this.retentionDays = Math.max(1, retentionDays);
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public int getMaxArticleChars() {
        return maxArticleChars;
    }

    public void setMaxArticleChars(int maxArticleChars) {
        this.maxArticleChars = maxArticleChars;
    }

    public boolean isTranslateEnabled() {
        return translateEnabled;
    }

    public void setTranslateEnabled(boolean translateEnabled) {
        this.translateEnabled = translateEnabled;
    }

    public String getTranslateFrom() {
        return translateFrom;
    }

    public void setTranslateFrom(String translateFrom) {
        this.translateFrom = translateFrom;
    }

    public String getTranslateTo() {
        return translateTo;
    }

    public void setTranslateTo(String translateTo) {
        this.translateTo = translateTo;
    }

    public String getTranslateEndpoint() {
        return translateEndpoint;
    }

    public void setTranslateEndpoint(String translateEndpoint) {
        this.translateEndpoint = translateEndpoint;
    }

    public int getTranslateChunkChars() {
        return translateChunkChars;
    }

    public void setTranslateChunkChars(int translateChunkChars) {
        this.translateChunkChars = translateChunkChars;
    }

    public int getTranslateDelayMs() {
        return translateDelayMs;
    }

    public void setTranslateDelayMs(int translateDelayMs) {
        this.translateDelayMs = translateDelayMs;
    }

    public List<Source> getSources() {
        return sources;
    }

    public void setSources(List<Source> sources) {
        this.sources = sources;
    }

    /** One configured news source */
    public static class Source {

        public enum Type { RSS, HTML }

        /** Display name, also used as the article attribution */
        private String name;

        /** RSS feed (channel/item or feed/entry) or HTML list page */
        private Type type = Type.RSS;

        /** URL of the RSS document or the HTML list page */
        private String listUrl;

        /** HTML source: selector matching the links on the list page */
        private String itemSelector = "a[href]";

        /** HTML source: an absolute link must match this regex to count as an article */
        private String linkPattern;

        /** Article body selector; several candidates may be listed, separated by commas */
        private String articleSelector = "article, .article-body, .article__content, .storytext, #storytext";

        /** Article title selector; falls back to og:title, then h1, then the list link text */
        private String titleSelector = "h1";

        /** Integer override of {@code maxItems} for this source */
        private Integer maxItems;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Type getType() {
            return type;
        }

        public void setType(Type type) {
            this.type = type;
        }

        public String getListUrl() {
            return listUrl;
        }

        public void setListUrl(String listUrl) {
            this.listUrl = listUrl;
        }

        public String getItemSelector() {
            return itemSelector;
        }

        public void setItemSelector(String itemSelector) {
            this.itemSelector = itemSelector;
        }

        public String getLinkPattern() {
            return linkPattern;
        }

        public void setLinkPattern(String linkPattern) {
            this.linkPattern = linkPattern;
        }

        public String getArticleSelector() {
            return articleSelector;
        }

        public void setArticleSelector(String articleSelector) {
            this.articleSelector = articleSelector;
        }

        public String getTitleSelector() {
            return titleSelector;
        }

        public void setTitleSelector(String titleSelector) {
            this.titleSelector = titleSelector;
        }

        public Integer getMaxItems() {
            return maxItems;
        }

        public void setMaxItems(Integer maxItems) {
            this.maxItems = maxItems;
        }
    }
}
