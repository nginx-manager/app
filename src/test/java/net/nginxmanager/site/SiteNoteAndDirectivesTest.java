package net.nginxmanager.site;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 사이트 메모(031)와 관리자 추가 지시어(032). */
class SiteNoteAndDirectivesTest {

    final SiteConfigRenderer renderer = new SiteConfigRenderer();

    @Test
    void noteBecomesAConfigComment() {
        String conf = renderer.render("docs.local",
                SiteModuleOptionsTest.staticSite(SiteModuleOptionsTest.options("note", "결제 팀 운영\r\n문의 #pay-ops  ")));
        assertThat(conf).contains("# 메모: 결제 팀 운영\n#       문의 #pay-ops\n");
        assertThat(renderer.render("docs.local", SiteModuleOptionsTest.staticSite(SiteOptions.DEFAULTS)))
                .doesNotContain("# 메모");
    }

    @Test
    void noteValidation() {
        assertThat(SiteModuleOptionsTest.options("note", "  ").note()).isNull();
        assertThat(SiteModuleOptionsTest.options("note", "a".repeat(500)).isNoteValid()).isTrue();
        assertThat(SiteModuleOptionsTest.options("note", "a".repeat(501)).isNoteValid()).isFalse();
        assertThat(SiteModuleOptionsTest.options("note", "bell\u0007").isNoteValid()).isFalse();
    }

    @Test
    void directivesGoAtTheEndOfEveryServerBlock() {
        SiteForm form = new SiteForm("app.local", "", SiteType.PROXY, 80, null, "http://10.0.0.1:8080", null, true,
                false, null, false, null, null, null, null, null, null,
                SiteModuleOptionsTest.options("serverDirectives", "client_body_timeout 30s;\n\nlocation = /healthz {\n    return 200;\n}"));
        String conf = renderer.render("app.local", form,
                new net.nginxmanager.cert.CertificatePaths("/etc/nginx/certs/a.crt", "/etc/nginx/certs/a.key"));
        String block = "\n    # 추가 지시어\n    client_body_timeout 30s;\n\n    location = /healthz {\n        return 200;\n    }\n}\n";
        assertThat(conf.split(java.util.regex.Pattern.quote(block), -1)).hasSize(3);
    }

    @Test
    void directivesMustBalanceBracesAndQuotes() {
        for (String ok : List.of("location / { return 200 '}'; }", "# { 주석은 세지 않는다\nkeepalive_timeout 5s;",
                "add_header X-A \"a\\\"b{\";")) {
            assertThat(SiteModuleOptionsTest.options("serverDirectives", ok).isServerDirectivesValid()).as(ok).isTrue();
        }
        for (String bad : List.of("location / {", "}", "} location / {", "add_header X-A 'open;",
                "x".repeat(4001))) {
            assertThat(SiteModuleOptionsTest.options("serverDirectives", bad).isServerDirectivesValid()).as(bad).isFalse();
        }
    }

    @Test
    void noteIsSearchable() {
        SiteQueryRepository.SiteRow row = new SiteQueryRepository.SiteRow(1L, "pay.local", null, SiteType.STATIC, 80,
                true, false, 1L, false, null, "/srv/pay", null, null, null, null, false,
                "note=\\uACB0\\uC81C \\uD300 \\uC6B4\\uC601\n");
        assertThat(row.note()).isEqualTo("결제 팀 운영");
        assertThat(new SiteSearch("결제", null).matches(row)).isTrue();
        assertThat(new SiteSearch("배송", null).matches(row)).isFalse();
    }
}
