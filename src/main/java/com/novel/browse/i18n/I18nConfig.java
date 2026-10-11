package com.novel.browse.i18n;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Multi language support (i18n) of the REST API.
 * <p>
 * Messages live in {@code src/main/resources/i18n/messages*.properties}; {@code messages.properties} is the
 * English default and the fallback for every unsupported locale. The locale of a request is picked by
 * {@link AppLocaleResolver} from {@code ?lang=} or {@code Accept-Language}, English when neither is usable.
 */
@Configuration
public class I18nConfig implements WebMvcConfigurer {

    /** Bundles: {@code i18n/messages.properties} (English) plus {@code i18n/messages_<locale>.properties} */
    private static final String BASENAME = "i18n/messages";

    private static final Logger log = LoggerFactory.getLogger(I18nConfig.class);

    private final String defaultLocale;
    private final List<String> supportedLocales;

    public I18nConfig(@Value("${app.i18n.default-locale:en}") String defaultLocale,
                      @Value("${app.i18n.supported-locales:en,zh_CN}") List<String> supportedLocales) {
        this.defaultLocale = defaultLocale;
        this.supportedLocales = supportedLocales;
    }

    @Bean
    public MessageSource messageSource() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename(BASENAME);
        source.setDefaultEncoding("UTF-8");
        // Never fall back to the JVM locale: an unsupported language must resolve to the English default
        source.setFallbackToSystemLocale(false);
        return source;
    }

    @Bean
    public I18nSettings i18nSettings() {
        Locale fallback = Objects.requireNonNullElse(Locales.parse(defaultLocale), Locale.ENGLISH);
        List<Locale> supported = supportedLocales == null
                ? List.of()
                : supportedLocales.stream().map(Locales::parse).filter(Objects::nonNull).toList();
        if (supported.isEmpty()) {
            supported = List.of(fallback);
        }
        if (!supported.contains(fallback)) {
            log.warn("app.i18n.default-locale {} is not part of app.i18n.supported-locales {}", fallback, supported);
        }
        return new I18nSettings(fallback, supported);
    }

    @Bean
    public LocaleResolver localeResolver(I18nSettings settings) {
        return new AppLocaleResolver(settings);
    }

    /** Adds {@code Content-Language} so clients can tell which language was used */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new ContentLanguageInterceptor());
    }

    private static final class ContentLanguageInterceptor implements HandlerInterceptor {

        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
            response.setHeader("Content-Language", LocaleContextHolder.getLocale().toLanguageTag());
            return true;
        }
    }
}
