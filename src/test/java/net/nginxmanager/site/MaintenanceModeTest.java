package net.nginxmanager.site;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 점검 모드 렌더링·검증(021). */
class MaintenanceModeTest {

    final SiteConfigRenderer renderer = new SiteConfigRenderer();
    final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private static SiteForm staticSite(Object... options) {
        return new SiteForm("shop.local", "", SiteType.STATIC, 80, "/srv/shop", null, null, false, true, null,
                false, null, null, null, null, null, null, SiteModuleOptionsTest.options(options));
    }

    @Test
    void maintenanceAnswers503WithThePageExceptForAllowedAddresses() {
        SiteForm form = staticSite("maintenance", true, "maintenanceAllowFrom", "203.0.113.7, 10.0.0.0/8",
                "maintenanceMessage", "DB 작업 중입니다.\n<10분> 걸립니다 & 곧 돌아옵니다", "pathRoutes", "/api/ http://10.0.0.5");
        assertThat(validator.validate(form)).isEmpty();

        String conf = renderer.render("shop.local", form);
        assertThat(conf).contains("""
                geo $nm_maintenance_shop_local {
                    default 1;
                    203.0.113.7 0;
                    10.0.0.0/8 0;
                }
                """, "    error_page 503 @nginx_manager_maintenance;\n", "location @nginx_manager_maintenance {",
                "add_header Retry-After 600 always;",
                "<p>DB 작업 중입니다.<br>&lt;10분&gt; 걸립니다 &amp; 곧 돌아옵니다</p>");
        // 내용 location마다 503을 걸고, 인증서 발급 경로에는 걸지 않는다
        for (String path : new String[] {"location ^~ /api/ {", "location / {"}) {
            String block = conf.substring(conf.indexOf(path), conf.indexOf("\n    }\n", conf.indexOf(path)));
            assertThat(block).as(path).contains("if ($nm_maintenance_shop_local) {\n            return 503;\n        }");
        }
        String acme = conf.substring(conf.indexOf("location ^~ /.well-known/acme-challenge/"));
        assertThat(acme.substring(0, acme.indexOf("\n    }\n"))).doesNotContain("return 503");
    }

    @Test
    void defaultMessageIsUsedWhenEmptyAndNothingIsRenderedWhenOff() {
        String on = renderer.render("shop.local", staticSite("maintenance", true));
        assertThat(on).contains("서비스 점검 중입니다.", "We are down for maintenance.");

        String off = renderer.render("shop.local", staticSite("maintenanceMessage", "점검"));
        assertThat(off).doesNotContain("geo ", "error_page 503", "return 503");
    }

    @Test
    void messagesThatWouldBreakTheNginxStringAreRejected() {
        for (String bad : new String[] {"it's", "a\\b", "cost $5", "{x}"}) {
            assertThat(SiteModuleOptionsTest.options("maintenanceMessage", bad).isMaintenanceMessageValid())
                    .as(bad).isFalse();
        }
        assertThat(SiteModuleOptionsTest.options("maintenanceMessage", "x".repeat(501)).isMaintenanceMessageValid())
                .isFalse();
        assertThat(SiteModuleOptionsTest.options("maintenanceAllowFrom", "not-an-ip").isMaintenanceAllowFromValid())
                .isFalse();
    }

    @Test
    void siteListShowsMaintenanceFromStoredOptions() {
        SiteQueryRepository.SiteRow row = new SiteQueryRepository.SiteRow(1L, "shop.local", null, SiteType.STATIC, 80,
                true, false, 1L, false, null, "/srv/shop", null, null, null, null, false,
                "#comment\nmaintenance=true\ngzip=false\n");
        assertThat(row.maintenance()).isTrue();
        assertThat(new SiteQueryRepository.SiteRow(1L, "shop.local", null, SiteType.STATIC, 80, true, false, 1L,
                false, null, "/srv/shop", null, null, null, null, false, null).maintenance()).isFalse();
    }
}
