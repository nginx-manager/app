package net.nginxmanager.site;

import net.nginxmanager.site.SiteQueryRepository.SiteRow;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SiteRowTest {

    private static SiteRow row(SiteType type, String proxyTarget, String staticRoot, String redirectTarget) {
        return new SiteRow(1L, "app.local", null, type, 80, true, false, 1L, false,
                proxyTarget, staticRoot, redirectTarget, null, null, null);
    }

    @Test
    void targetsShowWhatTheSiteTypeUses() {
        assertThat(row(SiteType.PROXY, "http://a:1\n\n  http://b:2 ", "/srv/www", null).targets())
                .containsExactly("http://a:1", "http://b:2");
        assertThat(row(SiteType.STATIC, "http://a:1", "/srv/www", null).targets()).containsExactly("/srv/www");
        assertThat(row(SiteType.REDIRECT, null, null, "https://new.example.com").targets())
                .containsExactly("https://new.example.com");
        assertThat(row(SiteType.RAW, "http://a:1", null, null).targets()).isEmpty();
        assertThat(row(SiteType.PROXY, null, null, null).targets()).isEmpty();
    }
}
