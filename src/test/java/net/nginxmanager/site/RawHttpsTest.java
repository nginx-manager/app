package net.nginxmanager.site;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RawHttpsTest {

    static final String MINIO = """
            # MinIO
            upstream minio { server 10.0.0.5:9000; }

            server {
                listen 80;
                listen [::]:80;
                server_name storage.example.com www.storage.example.com *.cdn.example.com;
                client_max_body_size 0;

                location / {
                    proxy_set_header Host $host;
                    proxy_pass http://minio;
                }
            }
            """;

    @Test
    void domainsComeFromTheHttpServerBlockWithoutWildcards() {
        assertThat(RawHttps.domains(MINIO)).containsExactly("storage.example.com", "www.storage.example.com");
    }

    @Test
    void theChallengePathIsAddedOnceToEachHttpServerBlock() {
        String once = RawHttps.withAcmeChallenge(MINIO);

        assertThat(once).contains("location ^~ /.well-known/acme-challenge/ {\n"
                + "        root /var/lib/nginx-manager/acme-webroot;");
        assertThat(once.indexOf("listen 80;")).isGreaterThan(once.indexOf("acme-challenge"));
        assertThat(once).contains("upstream minio { server 10.0.0.5:9000; }");
        assertThat(RawHttps.withAcmeChallenge(once)).isEqualTo(once);
        assertThat(NginxConf.parse(once)).hasSize(2);
    }

    @Test
    void anHttpsBlockIsCopiedFromTheHttpBlock() {
        String https = RawHttps.withHttps(MINIO, "/etc/nginx/certs/s/1/fullchain.pem", "/etc/nginx/certs/s/1/privkey.pem");

        List<NginxConf.Directive> parsed = NginxConf.parse(https);
        assertThat(parsed).extracting(NginxConf.Directive::name).containsExactly("upstream", "server", "server");
        NginxConf.Directive copy = parsed.get(2);
        assertThat(copy.block()).filteredOn(d -> d.name().equals("listen")).extracting(NginxConf.Directive::args)
                .containsExactly(List.of("443", "ssl"), List.of("[::]:443", "ssl"));
        assertThat(copy.block()).filteredOn(d -> d.name().equals("ssl_certificate")).extracting(d -> d.arg(0))
                .containsExactly("/etc/nginx/certs/s/1/fullchain.pem");
        assertThat(copy.block()).anySatisfy(d -> assertThat(d.name()).isEqualTo("location"));
        assertThat(https).startsWith(MINIO.substring(0, MINIO.indexOf("server {")));
        assertThat(RawHttps.hasHttps(https)).isTrue();
        assertThat(RawHttps.withHttps(https, "a", "b")).isEqualTo(https);
    }

    @Test
    void redirectKeepsOnlyTheChallengeOnPort80() {
        String https = RawHttps.withHttps(RawHttps.withAcmeChallenge(MINIO),
                "/etc/nginx/certs/s/1/fullchain.pem", "/etc/nginx/certs/s/1/privkey.pem", true);

        List<NginxConf.Directive> parsed = NginxConf.parse(https);
        assertThat(parsed).extracting(NginxConf.Directive::name).containsExactly("upstream", "server", "server");
        NginxConf.Directive http = parsed.get(1);
        assertThat(http.block()).filteredOn(d -> d.name().equals("listen")).extracting(d -> d.arg(0))
                .containsExactly("80", "[::]:80");
        assertThat(http.block()).filteredOn(d -> d.name().equals("server_name")).extracting(NginxConf.Directive::args)
                .containsExactly(List.of("storage.example.com", "www.storage.example.com", "*.cdn.example.com"));
        assertThat(http.block()).filteredOn(d -> d.name().equals("location")).extracting(d -> d.args().getLast())
                .containsExactly("/.well-known/acme-challenge/", "/");
        assertThat(http.block().toString()).doesNotContain("proxy_pass", "client_max_body_size");
        NginxConf.Directive copy = parsed.get(2);
        assertThat(copy.block()).anySatisfy(d -> assertThat(d.name()).isEqualTo("client_max_body_size"));
        // 인증 경로는 80에만 있으면 된다
        assertThat(copy.block()).filteredOn(d -> d.name().equals("location")).extracting(d -> d.args().getLast())
                .containsExactly("/");
        assertThat(https).contains("return 301 https://$host$request_uri;");
        assertThat(RawHttps.withHttps(https, "a", "b", true)).isEqualTo(https);
    }

    @Test
    void bracesInCommentsAndQuotesDoNotCount() {
        String content = """
                server {
                    listen 80; # {
                    server_name a.example.com;
                    return 200 "}";
                }
                """;
        assertThat(RawHttps.serverBlocks(content)).hasSize(1);
        assertThat(RawHttps.serverBlocks(content).getFirst().text()).endsWith("\"}\";\n}");
    }

    static final String TWO_PORTS = """
            server {
                listen 80;
                server_name storage.example.com;
                location / { proxy_pass http://minio_console; }
            }

            server {
                listen 9000;
                listen [::]:9000;
                server_name s3.example.com;
                location / { proxy_pass http://minio_s3; }
            }
            """;

    @Test
    void onlyServerBlocksListeningOnPort80GetTheChallengePathAndCertificate() {
        String acme = RawHttps.withAcmeChallenge(TWO_PORTS);

        assertThat(acme.split("acme-challenge", -1)).hasSize(2);
        assertThat(acme.indexOf("acme-challenge")).isLessThan(acme.indexOf("listen 9000;"));
        assertThat(RawHttps.domains(TWO_PORTS)).containsExactly("storage.example.com");

        String https = RawHttps.withHttps(acme, "/c.pem", "/k.pem");
        assertThat(https).contains("listen 9000;").contains("listen [::]:9000;");
        assertThat(https.indexOf("listen 443 ssl;")).isLessThan(https.indexOf("listen 9000;"));
    }

    @Test
    void reimportingKeepsTheChallengePathAndHttpsBlockTheManagerAdded() {
        String previous = RawHttps.withHttps(RawHttps.withAcmeChallenge(MINIO),
                "/etc/nginx/certs/storage/2/fullchain.pem", "/etc/nginx/certs/storage/2/privkey.pem", true);
        String source = MINIO.replace("10.0.0.5:9000", "10.0.0.6:9000");

        String again = RawHttps.reapply(previous, source);

        assertThat(again).contains("upstream minio { server 10.0.0.6:9000; }");
        assertThat(again).contains("ssl_certificate /etc/nginx/certs/storage/2/fullchain.pem;")
                .contains("ssl_certificate_key /etc/nginx/certs/storage/2/privkey.pem;")
                .contains("return 301 https://$host$request_uri;");
        assertThat(RawHttps.hasHttps(again)).isTrue();
        assertThat(again.split("acme-challenge", -1)).hasSize(2);
        assertThat(RawHttps.reapply(previous, again)).isEqualTo(again);
    }

    @Test
    void reimportingASiteWithoutManagedHttpsLeavesTheSourceAlone() {
        assertThat(RawHttps.reapply(MINIO, MINIO.replace("10.0.0.5", "10.0.0.6")))
                .isEqualTo(MINIO.replace("10.0.0.5", "10.0.0.6"));
    }
}
