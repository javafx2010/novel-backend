package com.novel.browse.i18n;

import com.novel.browse.domain.Novel;

import java.util.Locale;

/**
 * Language of a book (one work may exist in several language versions).
 * <p>
 * Tags follow the {@code app.i18n.supported-locales} form ({@code en}, {@code zh_CN}): {@code zh}, {@code zh-CN}
 * and {@code zh-Hans} all normalize to {@code zh_CN}, {@code en-US} to {@code en}. A language outside the
 * supported set (say {@code fr}) is stored as given, so the library is not limited to the UI languages;
 * when nothing is supplied the default locale ({@code app.i18n.default-locale}, English unless configured
 * otherwise) applies.
 */
public final class BookLanguage {

    /** Column length of {@code nb_novel.language} */
    private static final int MAX_LENGTH = 16;

    private BookLanguage() {
    }

    /**
     * Normalizes a language tag.
     *
     * @param tag      requested language, {@code null} or blank means "not specified"
     * @param settings supported and default locales of the service
     * @return the normalized tag, never blank
     */
    public static String normalize(String tag, I18nSettings settings) {
        String raw = tag == null ? null : tag.trim();
        Locale matched = Locales.match(Locales.parse(raw), settings.supportedLocales());
        if (matched != null) {
            String normalized = Locales.tag(matched);
            if (!normalized.isEmpty()) {
                return normalized;
            }
        }
        if (raw != null && !raw.isEmpty()) {
            return raw.length() > MAX_LENGTH ? raw.substring(0, MAX_LENGTH) : raw;
        }
        return defaultTag(settings);
    }

    /** The language assumed when a book carries none: the default locale of the service */
    public static String defaultTag(I18nSettings settings) {
        String tag = Locales.tag(settings.defaultLocale());
        return tag.isEmpty() ? Novel.DEFAULT_LANGUAGE : tag;
    }
}
