package com.novel.browse.i18n;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.LocaleResolver;

import java.util.Enumeration;
import java.util.Locale;

/**
 * Picks the locale of a request: {@code ?lang=} first, then {@code Accept-Language}, then the default locale
 * (English). Requested locales outside the configured whitelist fall back to the default locale.
 * <p>
 * Stateless on purpose: the language travels with every request, nothing is stored in a session or cookie.
 */
public class AppLocaleResolver implements LocaleResolver {

    /** Query parameter overriding {@code Accept-Language}, e.g. {@code ?lang=zh_CN} */
    public static final String PARAM = "lang";

    private final I18nSettings settings;

    public AppLocaleResolver(I18nSettings settings) {
        this.settings = settings;
    }

    @Override
    public Locale resolveLocale(HttpServletRequest request) {
        if (request != null) {
            String lang = request.getParameter(PARAM);
            if (StringUtils.hasText(lang)) {
                Locale requested = Locales.match(Locales.parse(lang), settings.supportedLocales());
                if (requested != null) {
                    return requested;
                }
            }
            Enumeration<Locale> accepted = request.getLocales();
            if (accepted != null) {
                while (accepted.hasMoreElements()) {
                    Locale matched = Locales.match(accepted.nextElement(), settings.supportedLocales());
                    if (matched != null) {
                        return matched;
                    }
                }
            }
        }
        return settings.defaultLocale();
    }

    @Override
    public void setLocale(HttpServletRequest request, HttpServletResponse response, Locale locale) {
        // The language is carried by the request itself; there is nothing to store
    }
}
