package com.novel.browse.news;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Manual news crawl triggers. Lives under {@code /admin/**}, so the admin token applies.
 */
@RestController
@RequestMapping("/admin/news")
public class NewsAdminController {

    private final NewsCrawlerService crawler;
    private final NewsProperties properties;

    public NewsAdminController(NewsCrawlerService crawler, NewsProperties properties) {
        this.crawler = crawler;
        this.properties = properties;
    }

    /** Runs one crawl immediately (same pipeline as the scheduled run) */
    @PostMapping("/run")
    public Map<String, Object> run() {
        return crawler.run();
    }

    /** The effective crawler configuration */
    @GetMapping("/config")
    public Map<String, Object> config() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enabled", properties.isEnabled());
        body.put("cron", properties.getCron());
        body.put("categoryName", properties.getCategoryName());
        body.put("language", properties.getLanguage());
        body.put("titlePrefix", properties.getTitlePrefix());
        body.put("maxItems", properties.getMaxItems());
        body.put("retentionDays", properties.getRetentionDays());
        body.put("translateEnabled", properties.isTranslateEnabled());
        body.put("translateFrom", properties.getTranslateFrom());
        body.put("translateTo", properties.getTranslateTo());
        body.put("sources", properties.getSources().stream()
                .map(source -> Map.of("name", source.getName(), "type", source.getType().name(),
                        "listUrl", source.getListUrl()))
                .toList());
        return body;
    }
}
