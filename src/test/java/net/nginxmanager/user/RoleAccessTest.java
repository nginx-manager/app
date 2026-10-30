package net.nginxmanager.user;

import net.nginxmanager.support.ConfigRootTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** contracts/http-routes.md의 역할 표를 그대로 검증한다. 허용이면 403이 아니고, 거부면 403이다. */
class RoleAccessTest extends ConfigRootTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    SetupState setupState;

    @BeforeEach
    void completeSetup() {
        userRepository.save(new User("admin", "{noop}unused-password", Role.ADMIN));
        setupState.reset();
    }

    @ParameterizedTest(name = "{0} {1} 최소 역할 {2}")
    @CsvSource({
            "GET,  /,                    MEMBER",
            "GET,  /sites,               MEMBER",
            "GET,  /sites/new,           MEMBER",
            "POST, /sites/new,           MEMBER",
            "POST, /sites/1,             MEMBER",
            "POST, /sites/1/enabled,     MEMBER",
            "POST, /sites/1/owner,       ADMIN",
            "POST, /sites/1/reimport,    ADMIN",
            "GET,  /sites/import,        ADMIN",
            "GET,  /files,               ADMIN",
            "POST, /files/1,             ADMIN",
            "GET,  /streams,             ADMIN",
            "POST, /streams/new,         ADMIN",
            "GET,  /apply,               MEMBER",
            "POST, /apply,               MEMBER",
            "GET,  /versions,            ADMIN",
            "POST, /versions/1/rollback, ADMIN",
            "POST, /drift/import,        ADMIN",
            "GET,  /users,               ADMIN",
            "POST, /users,               ADMIN",
            "POST, /users/1/role,        ADMIN",
            "GET,  /audit,               ADMIN",
            "GET,  /certs,               ADMIN",
            "POST, /certs/1/renew,       MEMBER",
            "POST, /certs/upload,        ADMIN",
            "POST, /certs/letsencrypt,   MEMBER",
            "GET,  /settings/acme,       ADMIN",
            "POST, /settings/acme,       ADMIN",
            "GET,  /settings/notifications, ADMIN",
            "POST, /settings/notifications, ADMIN",
            "GET,  /logs/1,              MEMBER",
            "POST, /runtime/start,       ADMIN",
            "GET,  /account,             MEMBER",
            "GET,  /guide,               MEMBER",
            "GET,  /guide/04-sites,      MEMBER",
            "POST, /account,             MEMBER",
    })
    void routeRequiresRole(String method, String path, Role minimum) throws Exception {
        for (Role role : Role.values()) {
            MockHttpServletRequestBuilder request = method.equals("GET") ? get(path) : post(path).with(csrf());
            int status = mvc.perform(request.with(user("u-" + role).roles(role.name())))
                    .andReturn().getResponse().getStatus();
            boolean allowed = role.compareTo(minimum) <= 0;
            if (allowed) {
                assertThat(status).as("%s %s as %s", method, path, role).isNotEqualTo(403);
            } else {
                assertThat(status).as("%s %s as %s", method, path, role).isEqualTo(403);
            }
        }
    }
}
