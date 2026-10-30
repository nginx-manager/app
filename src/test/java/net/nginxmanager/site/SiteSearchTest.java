package net.nginxmanager.site;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 사이트 목록 검색(027). */
class SiteSearchTest {

    private static SiteQueryRepository.SiteRow row(String domain, String extra, SiteType type, String target,
                                                   boolean enabled, boolean pending, String options) {
        return new SiteQueryRepository.SiteRow(1L, domain, extra, type, 80, enabled, pending, 1L, false,
                type == SiteType.PROXY ? target : null, type == SiteType.STATIC ? target : null,
                type == SiteType.REDIRECT ? target : null, null, null, null, false, options);
    }

    final List<SiteQueryRepository.SiteRow> rows = List.of(
            row("App.Example.com", "www.app.example.com", SiteType.PROXY, "http://10.0.0.21:8080\nhttp://10.0.0.22:8080",
                    true, false, null),
            row("docs.example.com", null, SiteType.STATIC, "/srv/www/docs", false, false, null),
            row("old.example.com", null, SiteType.REDIRECT, "https://new.example.com", true, true,
                    "maintenance=true\n"));

    private List<String> domains(String q, String status) {
        return new SiteSearch(q, status).filter(rows).stream().map(SiteQueryRepository.SiteRow::primaryDomain).toList();
    }

    @Test
    void textMatchesDomainsAndTargetsIgnoringCase() {
        assertThat(domains("10.0.0.22", null)).containsExactly("App.Example.com");
        assertThat(domains("WWW.APP", null)).containsExactly("App.Example.com");
        assertThat(domains("/srv/www", null)).containsExactly("docs.example.com");
        assertThat(domains("new.example", null)).containsExactly("old.example.com");
        assertThat(domains("  ", "")).hasSize(3);
        assertThat(new SiteSearch(" ", null).active()).isFalse();
    }

    @Test
    void statusFilters() {
        assertThat(domains(null, "disabled")).containsExactly("docs.example.com");
        assertThat(domains(null, "pending")).containsExactly("old.example.com");
        assertThat(domains(null, "maintenance")).containsExactly("old.example.com");
        assertThat(domains("example", "enabled")).containsExactly("App.Example.com", "old.example.com");
    }
}
