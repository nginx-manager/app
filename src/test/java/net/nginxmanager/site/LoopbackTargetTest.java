package net.nginxmanager.site;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** nginx 컨테이너 안의 127.0.0.1·localhost는 컨테이너 자신이라 프록시 대상으로 받지 않는다(v1.9.3). */
class LoopbackTargetTest {

    final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private static SiteForm proxy(String targets, SiteOptions options) {
        return new SiteForm("app.local", "", SiteType.PROXY, 80, null, targets, null, false, true, null, true, 5, 30,
                null, null, null, null, options);
    }

    private List<String> violations(SiteForm form) {
        return validator.validate(form).stream().map(ConstraintViolation::getPropertyPath).map(Object::toString)
                .toList();
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://127.0.0.1:8422", "http://localhost:8080", "https://127.0.1.1",
            "http://0.0.0.0:3000/app", "grpc://127.0.0.1:50051"})
    void rejectsLoopbackBackends(String target) {
        assertThat(violations(proxy(target, SiteModuleOptionsTest.options()))).contains("targetNotLoopback");
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://host.docker.internal:8422", "http://10.116.64.12:8422", "http://app:8080"})
    void acceptsAddressesTheContainerCanReach(String target) {
        assertThat(violations(proxy(target, SiteModuleOptionsTest.options()))).isEmpty();
    }

    @Test
    void managerPortsKeepTheirOwnMessage() {
        assertThat(violations(proxy("http://127.0.0.1:9000", SiteModuleOptionsTest.options())))
                .contains("proxyTargetNotManager").doesNotContain("targetNotLoopback");
    }

    @Test
    void checksBackupsAndPathRoutesToo() {
        assertThat(violations(proxy("http://10.0.0.1:8080",
                SiteModuleOptionsTest.options("backupTargets", "http://127.0.0.1:8080"))))
                .contains("targetNotLoopback");
        assertThat(violations(proxy("http://10.0.0.1:8080",
                SiteModuleOptionsTest.options("pathRoutes", "/api/ http://localhost:3000/"))))
                .contains("targetNotLoopback");
        assertThat(violations(proxy("http://10.0.0.1:8080",
                SiteModuleOptionsTest.options("pathRoutes", "/files/ /srv/uploads")))).isEmpty();
    }

    @Test
    void phpFpmOnTheHost() {
        assertThat(violations(new SiteForm("blog.local", "", SiteType.PHP, 80, "/srv/www/blog", "127.0.0.1:9000",
                null))).contains("targetNotLoopback");
        assertThat(violations(new SiteForm("blog.local", "", SiteType.PHP, 80, "/srv/www/blog",
                "unix:/run/php/php-fpm.sock", null))).isEmpty();
    }

    @Test
    void toHostKeepsPortAndPath() {
        assertThat(Loopback.toHost("http://127.0.0.1:8422/app/")).isEqualTo("http://host.docker.internal:8422/app/");
        assertThat(Loopback.toHost("localhost:9000")).isEqualTo("host.docker.internal:9000");
        assertThat(Loopback.toHost("[::1]:5432")).isEqualTo("host.docker.internal:5432");
        assertThat(Loopback.toHost("http://10.0.0.5:8080")).isEqualTo("http://10.0.0.5:8080");
        assertThat(Loopback.toHost("unix:/run/php/php-fpm.sock")).isEqualTo("unix:/run/php/php-fpm.sock");
    }
}
