package com.novel.browse.i18n;

import java.util.List;
import java.util.Locale;

/**
 * Parsing and matching of the locales supported by the service.
 */
public final class Locales {

    private Locales() {
    }

    /**
     * Parses a language tag such as {@code en}, {@code zh_CN} or {@code zh-CN}.
     *
     * @return the parsed locale, or {@code null} when the tag carries no language
     */
    public static Locale parse(String tag) {
        if (tag == null || tag.isBlank()) {
            return null;
        }
        Locale locale = Locale.forLanguageTag(tag.trim().replace('_', '-'));
        return locale.getLanguage().isEmpty() ? null : locale;
    }

    /**
     * Normalizes a locale: {@code zh} without a country is treated as Simplified Chinese.
     *
     * @return the normalized locale, or {@code null} when it carries no language
     */
    public static Locale normalize(Locale locale) {
        if (locale == null || locale.getLanguage().isEmpty()) {
            return null;
        }
        if ("zh".equals(locale.getLanguage()) && locale.getCountry().isEmpty()) {
            return Locale.SIMPLIFIED_CHINESE;
        }
        return locale;
    }

    /**
     * Picks the supported locale matching the requested one: exact match first, then by language.
     *
     * @return the supported locale, or {@code null} when none matches
     */
    public static Locale match(Locale requested, List<Locale> supported) {
        Locale candidate = normalize(requested);
        if (candidate == null) {
            return null;
        }
        for (Locale locale : supported) {
            if (locale.equals(candidate)) {
                return locale;
            }
        }
        if (!candidate.getCountry().isEmpty()) {
            for (Locale locale : supported) {
                if (locale.getLanguage().equals(candidate.getLanguage())) {
                    return locale;
                }
            }
        }
        return null;
    }

    /**
     * Resolves a requested locale to a supported one, falling back to {@code fallback}.
     */
    public static Locale resolve(Locale requested, List<Locale> supported, Locale fallback) {
        Locale matched = match(requested, supported);
        return matched != null ? matched : fallback;
    }

    /**
     * Renders a locale in the tag form used by the configuration, e.g. {@code en} and {@code zh_CN}.
     *
     * @return the tag, or an empty string when the locale carries no language
     */
    public static String tag(Locale locale) {
        if (locale == null || locale.getLanguage().isEmpty()) {
            return "";
        }
        return locale.getCountry().isEmpty()
                ? locale.getLanguage()
                : locale.getLanguage() + "_" + locale.getCountry();
    }
}
