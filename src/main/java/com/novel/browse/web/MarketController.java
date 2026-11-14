package com.novel.browse.web;

import com.novel.browse.service.MarketService;
import com.novel.browse.web.dto.MarketBook;
import com.novel.browse.web.dto.PageResult;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/**
 * Book market: browse the downloadable books and download their EPUB to a local device.
 * <p>
 * There is no purchase / payment step: every book with a stored EPUB is listed and can be downloaded
 * without logging in.
 */
@RestController
@RequestMapping("/market")
public class MarketController {

    private static final MediaType EPUB_MEDIA_TYPE = MediaType.parseMediaType("application/epub+zip");

    private final MarketService marketService;

    public MarketController(MarketService marketService) {
        this.marketService = marketService;
    }

    /**
     * Market listing.
     *
     * @param language   restricts to one language version; omitted means the site language, {@code all} every version
     * @param categoryId restricts to one category
     * @param q          matches title / author / tags
     * @param sort       {@code update} (default, newest update first) / {@code hot} / {@code rating}
     */
    @GetMapping("/books")
    public PageResult<MarketBook> books(@RequestParam(required = false) String language,
                                        @RequestParam(required = false) Long categoryId,
                                        @RequestParam(value = "q", required = false) String keyword,
                                        @RequestParam(defaultValue = "update") String sort,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "20") int size) {
        return marketService.page(language, categoryId, keyword, sort, page, size);
    }

    /** One market entry, including its download path and file size */
    @GetMapping("/books/{id}")
    public MarketBook book(@PathVariable Long id) {
        return marketService.detail(id);
    }

    /** Streams the stored EPUB as an attachment so the client can save it locally */
    @GetMapping("/books/{id}/download")
    public ResponseEntity<Resource> download(@PathVariable Long id) {
        MarketService.Download download = marketService.download(id);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(download.fileName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(EPUB_MEDIA_TYPE)
                .contentLength(download.size())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(new FileSystemResource(download.file()));
    }
}
