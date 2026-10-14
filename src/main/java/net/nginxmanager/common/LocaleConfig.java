package net.nginxmanager.common;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.CookieLocaleResolver;
import org.springframework.web.servlet.i18n.LocaleChangeInterceptor;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 화면 언어. 사용자가 고른 언어(?lang=, 쿠키에 1년 기억)를 먼저 쓰고, 고른 적이 없으면 브라우저의
 * Accept-Language에서 지원하는 언어를 고른다. 둘 다 없으면 한국어로 보여 준다.
 */
@Configuration
public class LocaleConfig implements WebMvcConfigurer {

    public static final List<Locale> SUPPORTED = List.of(Locale.KOREAN, Locale.ENGLISH, Locale.JAPANESE,
            Locale.SIMPLIFIED_CHINESE);

    @Bean
    public LocaleResolver localeResolver() {
        CookieLocaleResolver resolver = new CookieLocaleResolver("nm-lang");
        resolver.setCookieMaxAge(Duration.ofDays(365));
        resolver.setDefaultLocaleFunction(LocaleConfig::fromBrowser);
        return resolver;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        LocaleChangeInterceptor interceptor = new LocaleChangeInterceptor() {
            @Override
            protected Locale parseLocaleValue(String localeValue) {
                Locale locale = supported(Locale.forLanguageTag(localeValue.replace('_', '-')));
                if (locale == null) {
                    throw new IllegalArgumentException("unsupported language: " + localeValue);
                }
                return locale;
            }
        };
        interceptor.setParamName("lang");
        interceptor.setIgnoreInvalidLocale(true);
        registry.addInterceptor(interceptor);
    }

    static Locale fromBrowser(HttpServletRequest request) {
        if (request.getHeader("Accept-Language") == null) {
            return Locale.KOREAN;
        }
        for (Locale locale : Collections.list(request.getLocales())) {
            Locale match = supported(locale);
            if (match != null) {
                return match;
            }
        }
        return Locale.KOREAN;
    }

    /** 지원하는 언어로 맞춘다. 중국어는 지역과 상관없이 간체로 보여 준다. */
    static Locale supported(Locale locale) {
        for (Locale candidate : SUPPORTED) {
            if (candidate.getLanguage().equals(locale.getLanguage())) {
                return candidate;
            }
        }
        return null;
    }
}
