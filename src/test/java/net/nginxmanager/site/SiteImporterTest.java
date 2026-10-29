package net.nginxmanager.site;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 가져올 설정 파일을 폼으로 옮길 수 있는지 판단(spec 005 FR-504). */
class SiteImporterTest {

    private final SiteImporter importer = new SiteImporter(Validation.buildDefaultValidatorFactory().getValidator());
    private final SiteConfigRenderer renderer = new SiteConfigRenderer();

    @Test
    void proxyWithUpstreamAndWebsocketBecomesAFormSite() {
        String conf = """
                upstream app_backend {
                    least_conn;
                    server 10.0.0.1:8080;
                    server 10.0.0.2:8080;
                }
                map $http_upgrade $connection_upgrade {
                    default upgrade;
                    ''      close;
                }
                server {
                    listen 80;
                    listen [::]:80;
                    server_name App.Example.com www.app.example.com;
                    access_log /var/log/nginx/app.log;
                    location / {
                        proxy_pass http://app_backend;
                        proxy_http_version 1.1;
                        proxy_set_header Host $host;
                        proxy_set_header Upgrade $http_upgrade;
                        proxy_set_header Connection $connection_upgrade;
                        proxy_read_timeout 2m;
                    }
                }
                """;

        SiteImportAnalysis analysis = importer.analyze(conf, null);

        assertThat(analysis.unsupported()).isEmpty();
        assertThat(analysis.primaryDomain()).isEqualTo("app.example.com");
        assertThat(analysis.domains()).containsExactly("app.example.com", "www.app.example.com");
        SiteForm form = analysis.form();
        assertThat(form.type()).isEqualTo(SiteType.PROXY);
        assertThat(form.proxyTargets()).containsExactly("http://10.0.0.1:8080", "http://10.0.0.2:8080");
        assertThat(form.proxyLoadBalancing()).isEqualTo(LoadBalancing.LEAST_CONN);
        assertThat(form.proxyWebsocket()).isTrue();
        assertThat(form.proxyReadTimeout()).isEqualTo(120);
        assertThat(form.extraDomainsNormalized()).isEqualTo("www.app.example.com");
        assertThat(analysis.changes()).extracting(SiteImportAnalysis.Note::key)
                .contains("import.change.logs", "import.change.ipv6");
    }

    @Test
    void staticSiteWithOptions() {
        String conf = """
                server {
                    server_name docs.example.com;
                    root /srv/docs/;
                    client_max_body_size 2g;
                    gzip on;
                    location / {
                        autoindex on;
                        try_files $uri $uri/ =404;
                    }
                }
                """;

        SiteForm form = importer.analyze(conf, null).form();

        assertThat(form.type()).isEqualTo(SiteType.STATIC);
        assertThat(form.staticRoot()).isEqualTo("/srv/docs");
        assertThat(form.listenHttpPort()).isEqualTo(80);
        assertThat(form.options().gzip()).isTrue();
        assertThat(form.options().autoindex()).isTrue();
        assertThat(form.options().maxBodySizeMb()).isEqualTo(2048);
    }

    @Test
    void serverLevelReturnIsARedirectSite() {
        SiteImportAnalysis analysis = importer.analyze("""
                server { listen 8080; server_name old.example.com;
                    return 308 https://new.example.com$request_uri; }
                """, null);

        SiteForm form = analysis.form();
        assertThat(form.type()).isEqualTo(SiteType.REDIRECT);
        assertThat(form.redirectTarget()).isEqualTo("https://new.example.com");
        assertThat(form.redirectCode()).isEqualTo(308);
        assertThat(form.redirectKeepPath()).isTrue();
        assertThat(form.listenHttpPort()).isEqualTo(8080);
    }

    @Test
    void whatTheFormWouldWriteBehavesTheSameForASimpleProxy() {
        String conf = """
                server {
                    listen 80;
                    server_name api.example.com;
                    location / {
                        proxy_pass http://127.0.0.1:3000;
                    }
                }
                """;

        SiteImportAnalysis analysis = importer.analyze(conf, null);
        String rendered = renderer.render("api.example.com", analysis.form());

        assertThat(rendered).contains("listen 80;", "server_name api.example.com;", "proxy_pass http://127.0.0.1:3000;");
        // 폼이 만든 설정을 다시 읽어도 같은 폼이 된다
        assertThat(importer.analyze(rendered, null).form()).isEqualTo(analysis.form());
    }

    @Test
    void anythingTheFormCannotHoldKeepsTheFileRaw() {
        SiteImportAnalysis analysis = importer.analyze("""
                server {
                    listen 80;
                    server_name shop.example.com;
                    location / { proxy_pass http://127.0.0.1:3000; }
                    location /static/ { alias /srv/static/; }
                    error_page 502 /502.html;
                }
                """, null);

        assertThat(analysis.importable()).isTrue();
        assertThat(analysis.formPossible()).isFalse();
        assertThat(analysis.unsupported()).extracting(SiteImportAnalysis.Note::key, SiteImportAnalysis.Note::arg)
                .contains(org.assertj.core.groups.Tuple.tuple("import.reason.location", "/static/"),
                        org.assertj.core.groups.Tuple.tuple("import.reason.directive", "error_page"));
    }

    @Test
    void httpsAndSeveralServersAreRaw() {
        SiteImportAnalysis analysis = importer.analyze("""
                server { listen 80; server_name a.example.com; return 301 https://$host$request_uri; }
                server { listen 443 ssl; server_name a.example.com; ssl_certificate /etc/nginx/certs/a.pem;
                         location / { proxy_pass http://app:8080; } }
                """, null);

        assertThat(analysis.serverCount()).isEqualTo(2);
        assertThat(analysis.formPossible()).isFalse();
        assertThat(analysis.primaryDomain()).isEqualTo("a.example.com");
        assertThat(analysis.unsupported()).extracting(SiteImportAnalysis.Note::key)
                .contains("import.reason.multipleServers");
    }

    @Test
    void filesThatAreNotSitesAreRefused() {
        assertThat(importer.analyze("events {}\nhttp { server { listen 80; } }\n", null).errorKey())
                .isEqualTo("import.error.mainConfig");
        assertThat(importer.analyze("upstream a { server 10.0.0.1; }\n", null).errorKey())
                .isEqualTo("import.error.noServer");
        assertThat(importer.analyze("server { listen 80;\n", null).errorKey()).isEqualTo("import.error.syntax");
    }

    @Test
    void withoutADomainThePrimaryMustBeGiven() {
        String conf = "server { listen 80 default_server; server_name _; location / { root /srv/www; } }\n";

        assertThat(importer.analyze(conf, null).primaryDomain()).isNull();
        assertThat(importer.analyze(conf, null).form()).isNull();
        assertThat(importer.analyze(conf, "www.example.com").primaryDomain()).isEqualTo("www.example.com");
    }

    @Test
    void quotedSeparatorsAndVariablesAreParsed() {
        List<NginxConf.Directive> parsed = NginxConf.parse("""
                server { # comment ; {
                    add_header X-A "a;b{c}" always;
                    set $v "${host}x";
                }
                """);

        assertThat(parsed.getFirst().block()).extracting(NginxConf.Directive::args)
                .containsExactly(List.of("X-A", "a;b{c}", "always"), List.of("$v", "${host}x"));
    }
}
