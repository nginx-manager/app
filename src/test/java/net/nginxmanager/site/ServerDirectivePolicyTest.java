package net.nginxmanager.site;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 035: 담당 멤버가 넣을 수 있는 지시어와 관리자만 넣는 지시어. */
class ServerDirectivePolicyTest {

    @Test
    void basicDirectivesAreForEveryone() {
        for (ServerDirectivePolicy.Example example : ServerDirectivePolicy.EXAMPLES) {
            assertThat(ServerDirectivePolicy.adminOnly(example.code())).as(example.code()).isEmpty();
        }
    }

    @Test
    void siteDirectivesAreForOwners() {
        assertThat(ServerDirectivePolicy.adminOnly("""
                root /srv/www/app;
                location /files/ { alias /srv/files/; }
                listen 8080;
                server_name www.app.local;
                ssl_certificate /etc/ssl/app.pem;
                ssl_certificate_key /etc/ssl/app.key;
                auth_basic_user_file /etc/nginx/htpasswd/app;
                access_log off;
                """)).isEmpty();
    }

    @Test
    void fileWritingAndCodeLoadingDirectivesAreFoundInsideBlocks() {
        assertThat(ServerDirectivePolicy.adminOnly("""
                client_body_timeout 30s;
                location /x {
                    if ($arg_a) { access_log /etc/cron.d/x; }
                }
                listen unix:/run/x.sock;
                proxy_temp_path /tmp/x;
                proxy_store on;
                js_content main.handler;
                Include /etc/nginx/*.conf;
                error_log /tmp/e.log;
                """)).containsExactly("access_log /etc/cron.d/x", "listen unix:/run/x.sock", "proxy_temp_path /tmp/x",
                "proxy_store on", "js_content main.handler", "Include /etc/nginx/*.conf", "error_log /tmp/e.log");
    }

    @Test
    void listenAndServerNameAreReadForConflictChecks() {
        String directives = "listen 8443 ssl;\nlisten [::]:9000;\nlisten 127.0.0.1:81;\nserver_name WWW.App.local *.app.local _;";
        assertThat(ServerDirectivePolicy.listenPorts(directives)).containsExactly(8443, 9000, 81);
        assertThat(ServerDirectivePolicy.serverNames(directives)).containsExactly("www.app.local");
    }

    @Test
    void unreadableTextIsAdminOnly() {
        assertThat(ServerDirectivePolicy.adminOnly("client_body_timeout 30s")).containsExactly(ServerDirectivePolicy.UNREADABLE);
    }

    @Test
    void onlyNewlyAddedDirectivesCount() {
        assertThat(ServerDirectivePolicy.added("include /a;\nexpires 1d;", "include /a;")).isEmpty();
        assertThat(ServerDirectivePolicy.added("include /b;", "include /a;")).containsExactly("include /b");
        assertThat(ServerDirectivePolicy.names(java.util.List.of("root /srv/b", "root /x", "alias /y"))).containsExactly("root", "alias");
    }
}
