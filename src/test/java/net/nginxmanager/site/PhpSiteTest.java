package net.nginxmanager.site;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** PHP-FPM 사이트 폼(spec 016). */
class PhpSiteTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
    private final SiteConfigRenderer renderer = new SiteConfigRenderer();
    private final SiteImporter importer = new SiteImporter(validator);

    private static SiteForm php(String root, String fpm) {
        return new SiteForm("blog.local", "", SiteType.PHP, 80, root, fpm, null);
    }

    @Test
    void rendersAFrontControllerAndPassesPhpToFpm() {
        String conf = renderer.render("blog.local", php("/srv/www/blog", "127.0.0.1:9000"));

        assertThat(conf).contains("    root /srv/www/blog;\n    index index.php index.html;\n")
                .contains("        try_files $uri $uri/ /index.php?$query_string;\n")
                .contains("    location ~ \\.php$ {\n        try_files $uri =404;\n        fastcgi_pass 127.0.0.1:9000;\n")
                .contains("        include fastcgi_params;\n")
                .contains("        fastcgi_param SCRIPT_FILENAME $document_root$fastcgi_script_name;\n")
                .doesNotContain("proxy_pass")
                .doesNotContain("fastcgi_param HTTPS");
    }

    @Test
    void passwordAndAddressRulesAlsoProtectPhpFiles() {
        SiteOptions options = new SiteOptions(false, false, false, false, null, "10.0.0.0/8", null, "alice", false,
                null, null, null, null, null, null, false, null, null, null, null, null, null, null);
        SiteForm form = php("/srv/www/blog", "unix:/run/php/php8.3-fpm.sock").withOptions(options);

        String conf = renderer.render("blog.local", form);

        String phpLocation = conf.substring(conf.indexOf("location ~ \\.php$"));
        assertThat(phpLocation).contains("auth_basic \"blog.local\";", "allow 10.0.0.0/8;", "deny all;",
                "fastcgi_pass unix:/run/php/php8.3-fpm.sock;");
        String rootLocation = conf.substring(conf.indexOf("location / {"), conf.indexOf("location ~ \\.php$"));
        assertThat(rootLocation).contains("auth_basic \"blog.local\";", "allow 10.0.0.0/8;");
    }

    @Test
    void acceptsHostPortAndSockets() {
        assertThat(validator.validate(php("/srv/www/blog", "127.0.0.1:9000"))).isEmpty();
        assertThat(validator.validate(php("/srv/www/blog", "php-fpm:9000"))).isEmpty();
        assertThat(validator.validate(php("/srv/www/blog", "unix:/run/php/php-fpm.sock"))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://127.0.0.1:9000", "127.0.0.1", "a:9000;}", "a:9000 b:9000", "unix:/run/../x.sock",
            "a:70000", ""})
    void rejectsBadFpmAddresses(String target) {
        assertThat(validator.validate(php("/srv/www/blog", target))).isNotEmpty();
    }

    @Test
    void theDocumentRootFollowsTheStaticRules() {
        assertThat(validator.validate(php("/data", "127.0.0.1:9000"))).isNotEmpty();
        assertThat(validator.validate(php("relative", "127.0.0.1:9000"))).isNotEmpty();
        assertThat(validator.validate(php(null, "127.0.0.1:9000"))).isNotEmpty();
    }

    @Test
    void aTypicalPhpConfigIsImportedAsAPhpFormSite() {
        SiteImportAnalysis analysis = importer.analyze("""
                server {
                    listen 80;
                    server_name blog.example.com;
                    root /var/www/blog/;
                    index index.php index.html;

                    location / {
                        try_files $uri $uri/ /index.php?$args;
                    }

                    location ~ \\.php$ {
                        include fastcgi_params;
                        fastcgi_pass unix:/run/php/php8.3-fpm.sock;
                        fastcgi_param SCRIPT_FILENAME $document_root$fastcgi_script_name;
                    }
                }
                """, null);

        assertThat(analysis.formPossible()).isTrue();
        SiteForm form = analysis.form();
        assertThat(form.type()).isEqualTo(SiteType.PHP);
        assertThat(form.staticRoot()).isEqualTo("/var/www/blog");
        assertThat(form.proxyTarget()).isEqualTo("unix:/run/php/php8.3-fpm.sock");
        assertThat(validator.validate(form)).isEmpty();
    }

    @Test
    void phpLocationsWithOtherDirectivesStayRaw() {
        SiteImportAnalysis analysis = importer.analyze("""
                server {
                    listen 80;
                    server_name blog.example.com;
                    root /var/www/blog;
                    location ~ \\.php$ {
                        fastcgi_pass 127.0.0.1:9000;
                        fastcgi_cache blog;
                    }
                }
                """, null);

        assertThat(analysis.formPossible()).isFalse();
    }
}
