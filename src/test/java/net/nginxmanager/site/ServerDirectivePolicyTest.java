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
    void serverWideDirectivesAreFoundInsideBlocks() {
        assertThat(ServerDirectivePolicy.adminOnly("""
                client_body_timeout 30s;
                location /x {
                    if ($arg_a) { alias /etc/; }
                }
                listen 8443;
                ssl_certificate /etc/ssl/a.pem;
                proxy_temp_path /tmp/x;
                auth_basic_user_file /etc/passwd;
                js_content main.handler;
                Include /etc/nginx/*.conf;
                """)).containsExactly("alias /etc/", "listen 8443", "ssl_certificate /etc/ssl/a.pem",
                "proxy_temp_path /tmp/x", "auth_basic_user_file /etc/passwd", "js_content main.handler",
                "Include /etc/nginx/*.conf");
    }

    @Test
    void unreadableTextIsAdminOnly() {
        assertThat(ServerDirectivePolicy.adminOnly("client_body_timeout 30s")).containsExactly(ServerDirectivePolicy.UNREADABLE);
    }

    @Test
    void onlyNewlyAddedDirectivesCount() {
        assertThat(ServerDirectivePolicy.added("root /srv/a;\nexpires 1d;", "root /srv/a;")).isEmpty();
        assertThat(ServerDirectivePolicy.added("root /srv/b;", "root /srv/a;")).containsExactly("root /srv/b");
        assertThat(ServerDirectivePolicy.names(java.util.List.of("root /srv/b", "root /x", "alias /y"))).containsExactly("root", "alias");
    }
}
