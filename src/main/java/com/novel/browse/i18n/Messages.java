package com.novel.browse.i18n;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * Resolves message codes to the language of the current request.
 * <p>
 * The locale comes from {@link LocaleContextHolder} (set by {@link AppLocaleResolver} from {@code Accept-Language}
 * or {@code ?lang=}); anything outside the configured whitelist falls back to the default locale.
 */
@Component
public class Messages {

    private final MessageSource messageSource;
    private final I18nSettings settings;

    public Messages(MessageSource messageSource, I18nSettings settings) {
        this.messageSource = messageSource;
        this.settings = settings;
    }

    /** Resolves a code in the locale of the current request */
    public String get(String code, Object... args) {
        return get(LocaleContextHolder.getLocale(), code, args);
    }

    /** Resolves a code in an explicit locale; unknown codes resolve to the code itself */
    public String get(Locale locale, String code, Object... args) {
        return messageSource.getMessage(code, plain(args), code, resolve(locale));
    }

    /** Resolves the message carried by a {@link CodedException} in the locale of the current request */
    public String of(CodedException exception) {
        return get(exception.code(), exception.args());
    }

    /** Resolves the message carried by a {@link CodedException} in an explicit locale */
    public String of(Locale locale, CodedException exception) {
        return get(locale, exception.code(), exception.args());
    }

    /**
     * Numbers are passed as plain text: MessageFormat would otherwise render {@code 9999} as {@code 9,999}.
     */
    private static Object[] plain(Object[] args) {
        if (args == null || args.length == 0) {
            return args;
        }
        Object[] copy = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            copy[i] = args[i] instanceof Number number ? number.toString() : args[i];
        }
        return copy;
    }

    /** Maps any locale to a supported one, falling back to the default locale */
    public Locale resolve(Locale locale) {
        return Locales.resolve(locale, settings.supportedLocales(), settings.defaultLocale());
    }

    public Locale defaultLocale() {
        return settings.defaultLocale();
    }

    public List<Locale> supportedLocales() {
        return settings.supportedLocales();
    }
}
