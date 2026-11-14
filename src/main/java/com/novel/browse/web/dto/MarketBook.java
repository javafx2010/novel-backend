package com.novel.browse.web.dto;

import com.novel.browse.domain.Novel;

import java.io.Serial;
import java.io.Serializable;

/**
 * One book in the market: the usual summary plus the information a client needs to download the
 * EPUB file to a local device.
 *
 * @param novel       book summary (same shape as every other listing)
 * @param fileName    suggested file name for the downloaded copy, e.g. {@code 剑痕录.epub}
 * @param fileSize    size of the stored EPUB in bytes; {@code null} when the file is missing on the server
 * @param downloadUrl relative download path, e.g. {@code /market/books/12/download}
 */
public record MarketBook(NovelSummary novel, String fileName, Long fileSize, String downloadUrl)
        implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public static MarketBook of(Novel novel, String fileName, Long fileSize) {
        return new MarketBook(NovelSummary.of(novel), fileName, fileSize, downloadUrl(novel.getId()));
    }

    public static String downloadUrl(Long novelId) {
        return "/market/books/" + novelId + "/download";
    }
}
