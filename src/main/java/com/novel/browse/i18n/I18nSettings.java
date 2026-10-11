package com.novel.browse.i18n;

import java.util.List;
import java.util.Locale;

/**
 * Locales supported by the service: the fallback locale (English by default) and the whitelist a request
 * locale is matched against.
 */
public record I18nSettings(Locale defaultLocale, List<Locale> supportedLocales) {
}
