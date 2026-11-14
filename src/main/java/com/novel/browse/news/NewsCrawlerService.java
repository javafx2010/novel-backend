package com.novel.browse.news;

import com.novel.browse.domain.Novel;
import com.novel.browse.news.NewsProperties.Source;
import com.novel.browse.service.epub.TextUtil;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Crawls the configured US news sources (English), translates every article into Chinese
 * and hands the result to {@link NewsPublisher}.
 *
 * <p>Pipeline per run: list fetch (RSS first, HTML list page as fallback) &rarr; per article:
 * page fetch + body extraction &rarr; translation (original text kept on failure) &rarr; publish.
 */
@Service
public class NewsCrawlerService {

    private static final Logger log = LoggerFactory.getLogger(NewsCrawlerService.class);

    private final NewsProperties properties;
    private final NewsTranslator translator;
    private final NewsPublisher publisher;

    public NewsCrawlerService(NewsProperties properties, NewsTranslator translator, NewsPublisher publisher) {
        this.properties = properties;
        this.translator = translator;
        this.publisher = publisher;
    }

    /**
     * Runs the first crawl right after the application is fully started, so the news book exists
     * without waiting for the next cron tick. The crawl is network-bound and therefore runs on a
     * separate thread to keep the startup path free.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (!properties.isEnabled()) {
            log.info("News crawler disabled, skipping the startup crawl");
            return;
        }
        log.info("Application started, launching the startup news crawl on a separate thread");
        CompletableFuture.runAsync(() -> {
            try {
                run();
            } catch (Exception e) {
                log.error("Startup news crawl failed", e);
            }
        });
    }

    /** Scheduled crawl, see {@code app.news.cron} (hourly by default) */
    @Scheduled(cron = "${app.news.cron:0 0 * * * *}")
    public void scheduledRun() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            run();
        } catch (Exception e) {
            log.error("Scheduled news crawl failed", e);
        }
    }

    /** One crawl run; also called by the admin trigger. Returns a summary of what happened */
    public Map<String, Object> run() {
        if (!properties.isEnabled()) {
            return Map.of("enabled", false);
        }
        log.info("News crawl started: {} source(s), retentionDays={}", properties.getSources().size(),
                properties.getRetentionDays());
        Map<String, Object> summary = new LinkedHashMap<>();
        List<PendingArticle> accepted = new ArrayList<>();
        Map<String, Object> perSource = new LinkedHashMap<>();

        for (Source source : properties.getSources()) {
            Map<String, Object> stats = new LinkedHashMap<>();
            try {
                List<NewsItem> items = fetchList(source);
                int limit = source.getMaxItems() != null && source.getMaxItems() > 0
                        ? Math.min(source.getMaxItems(), properties.getMaxItems())
                        : properties.getMaxItems();
                stats.put("listed", items.size());
                int fetched = 0;
                int empty = 0;
                for (NewsItem item : items) {
                    if (fetched >= limit) {
                        break;
                    }
                    String body = fetchArticle(source, item);
                    if (body == null || body.isBlank()) {
                        empty++;
                        continue;
                    }
                    accepted.add(toPendingArticle(source, item, body));
                    fetched++;
                }
                stats.put("accepted", fetched);
                stats.put("emptyBody", empty);
                log.info("News source {}: listed={}, accepted={}, emptyBody={}", source.getName(),
                        items.size(), fetched, empty);
            } catch (Exception e) {
                log.warn("News source {} failed: {}", source.getName(), e.getMessage());
                stats.put("error", e.getMessage());
            }
            perSource.put(source.getName(), stats);
        }

        int translated = 0;
        int fellBack = 0;
        List<PendingArticle> chinese = new ArrayList<>(accepted.size());
        for (PendingArticle article : accepted) {
            try {
                chinese.add(translate(article));
                translated++;
            } catch (Exception e) {
                log.warn("Translation failed for {}: {}", article.url(), e.getMessage());
                chinese.add(keepOriginal(article));
                fellBack++;
            }
        }

        Novel book = null;
        int purged = 0;
        if (!chinese.isEmpty()) {
            book = publisher.publish(java.time.LocalDate.now(), chinese);
            // Keep only the last app.news.retention-days days of daily news books
            purged = publisher.purgeOlderThan(java.time.LocalDate.now().minusDays(properties.getRetentionDays()));
        }

        summary.put("date", java.time.LocalDate.now().toString());
        summary.put("sources", perSource);
        summary.put("translated", translated);
        summary.put("untranslatedFallback", fellBack);
        summary.put("publishedBook", book == null ? null : book.getTitle());
        summary.put("chapterCount", book == null ? 0 : book.getChapterCount());
        summary.put("purgedBooks", purged);
        log.info("News crawl finished: accepted={}, translated={}, untranslatedFallback={}, book={}, chapters={}, purgedBooks={}",
                accepted.size(), translated, fellBack,
                book == null ? "none" : book.getTitle(),
                book == null ? 0 : book.getChapterCount(), purged);
        return summary;
    }

    /** Fetches the news list: RSS feed for {@code RSS} sources, list page links for {@code HTML} */
    private List<NewsItem> fetchList(Source source) throws IOException {
        return switch (source.getType()) {
            case RSS -> fetchRss(source);
            case HTML -> fetchHtmlList(source);
        };
    }

    private List<NewsItem> fetchRss(Source source) throws IOException {
        Document xml = Jsoup.connect(source.getListUrl())
                .userAgent(properties.getUserAgent())
                .timeout(properties.getTimeoutSeconds() * 1000)
                .ignoreContentType(true)
                .parser(Parser.xmlParser())
                .get();
        Elements entries = xml.select("item");
        if (entries.isEmpty()) {
            entries = xml.select("entry");
        }
        List<NewsItem> items = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Element entry : entries) {
            String title = text(entry.selectFirst("title"));
            String link = text(entry.selectFirst("link"));
            if (link == null || link.isBlank()) {
                // Atom feeds keep the URL in the href attribute
                link = entry.selectFirst("link") == null ? "" : entry.selectFirst("link").attr("abs:href");
            }
            if (title == null || title.isBlank() || link == null || link.isBlank()) {
                continue;
            }
            if (!seen.add(link)) {
                continue;
            }
            items.add(new NewsItem(title, link, parseDate(entry)));
        }
        return items;
    }

    private List<NewsItem> fetchHtmlList(Source source) throws IOException {
        Document page = Jsoup.connect(source.getListUrl())
                .userAgent(properties.getUserAgent())
                .timeout(properties.getTimeoutSeconds() * 1000)
                .get();
        List<NewsItem> items = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Element link : page.select(source.getItemSelector())) {
            String url = link.absUrl("href");
            if (url == null || url.isBlank()) {
                continue;
            }
            if (source.getLinkPattern() != null && !source.getLinkPattern().isBlank()
                    && !url.matches(source.getLinkPattern())) {
                continue;
            }
            String title = link.text().trim();
            if (title.length() < 10 || !seen.add(url)) {
                continue;
            }
            items.add(new NewsItem(title, url, null));
        }
        return items;
    }

    /** Fetches the article page and extracts body paragraphs (TTS-friendly: links, ads and UI junk removed) */
    private String fetchArticle(Source source, NewsItem item) {
        try {
            Document page = Jsoup.connect(item.url())
                    .userAgent(properties.getUserAgent())
                    .timeout(properties.getTimeoutSeconds() * 1000)
                    .get();
            page.select("script, style, nav, header, footer, aside, form, button, iframe, svg, figure, figcaption")
                    .remove();
            // Ads, share bars, subscribe prompts, related-story teasers and such are noise for reading aloud
            page.select("[class*=advert], [class*=promo], [class*=sponsor], [class*=share], [class*=subscribe], "
                    + "[class*=newsletter], [class*=related], [class*=recommend], [class*=teaser]").remove();
            // Link text itself ("click here", site names) is not readable content
            page.select("a[href]").remove();
            List<String> paragraphs = new ArrayList<>();
            for (Element p : page.select(source.getArticleSelector())) {
                // The selector may match a container: paragraph tags inside it win
                Elements inner = p.select("p");
                if (!inner.isEmpty()) {
                    inner.forEach(el -> addParagraph(paragraphs, el.text()));
                } else {
                    addParagraph(paragraphs, p.text());
                }
                if (TextUtil.normalize(String.join("\n", paragraphs)).length() > properties.getMaxArticleChars()) {
                    break;
                }
            }
            return TextUtil.normalize(String.join("\n", paragraphs));
        } catch (Exception e) {
            log.warn("Article fetch failed {}: {}", item.url(), e.getMessage());
            return null;
        }
    }

    private static void addParagraph(List<String> paragraphs, String text) {
        String cleaned = TextUtil.normalize(text);
        if (cleaned.isBlank() || isUnreadableJunk(cleaned)) {
            return;
        }
        paragraphs.add(cleaned);
    }

    /**
     * Whether the line is noise for text-to-speech: raw URLs, e-mail addresses, subscription and
     * share prompts, credits, copyright boilerplate and fragments too short to be a sentence.
     */
    private static boolean isUnreadableJunk(String line) {
        String lower = line.toLowerCase();
        if (lower.contains("http://") || lower.contains("https://") || lower.contains("www.")
                || lower.matches(".*[\\w.+-]+@[\\w-]+\\.[\\w.]+.*")) {
            return true;
        }
        for (String phrase : UNREADABLE_PHRASES) {
            if (lower.contains(phrase)) {
                return true;
            }
        }
        return line.length() < 15;
    }

    /** Lowercase fragments marking boilerplate not worth reading aloud */
    private static final String[] UNREADABLE_PHRASES = {
            "advertisement", "advert", "read more", "continue reading", "share this", "share:",
            "sign up", "subscribe", "newsletter", "follow us", "follow npr", "follow cnn",
            "get our", "download our app", "listen now", "play episode", "all rights reserved",
            "copyright", "photo by", "image:", "image credit", "credit:", "getty", "ap photo",
            "support npr", "donate", "related:", "see also", "click here", "more from",
            "this article originally", "correction:", "et updated", "terms of use", "privacy policy"
    };

    /** Translates title and body; the body keeps the English title line for reference */
    private PendingArticle translate(PendingArticle article) throws Exception {
        String title = translator.translate(article.title());
        String body = translator.translate(article.body());
        log.info("Article translated: {} chars(en) -> {} chars(zh), url={}",
                article.body().length(), body.length(), article.url());
        StringBuilder content = new StringBuilder();
        // TTS-friendly header: no raw URLs (the link lives in Chapter.sourceUrl)
        content.append("来源：").append(article.sourceName()).append('\n');
        if (article.publishedAt() != null) {
            content.append("发布时间：").append(article.publishedAt()
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))).append('\n');
        }
        content.append('\n').append(body);
        return new PendingArticle(title, article.url(), article.sourceName(), article.publishedAt(),
                content.toString());
    }

    /** Translation failed: publish the original English text, clearly marked */
    private PendingArticle keepOriginal(PendingArticle article) {
        String body = article.body();
        StringBuilder content = new StringBuilder();
        content.append("来源：").append(article.sourceName()).append('\n');
        content.append("（译文生成失败，以下为英文原文）\n\n").append(body);
        return new PendingArticle(article.title(), article.url(), article.sourceName(),
                article.publishedAt(), content.toString());
    }

    private PendingArticle toPendingArticle(Source source, NewsItem item, String englishBody) {
        String body = TextUtil.truncate(englishBody, properties.getMaxArticleChars());
        return new PendingArticle(item.title(), item.url(), source.getName(), item.publishedAt(),
                body);
    }

    /** Accepts RFC 1123 (RSS pubDate) and ISO 8601 (Atom published/updated) */
    private static LocalDateTime parseDate(Element entry) {
        for (String tag : new String[]{"pubDate", "published", "updated", "dc:date"}) {
            String value = text(entry.selectFirst(tag));
            if (value == null || value.isBlank()) {
                continue;
            }
            try {
                return OffsetDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toLocalDateTime();
            } catch (Exception ignored) {
                // fall through to ISO
            }
            try {
                return OffsetDateTime.parse(value.trim()).toLocalDateTime();
            } catch (Exception ignored) {
                // give up, keep null
            }
        }
        return null;
    }

    private static String text(Element element) {
        return element == null ? null : element.text().trim();
    }

    /** One entry of a news list */
    private record NewsItem(String title, String url, LocalDateTime publishedAt) {
    }
}
