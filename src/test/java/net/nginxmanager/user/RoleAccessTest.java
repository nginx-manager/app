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
            "GET,  /,                    VIEWER",
            "GET,  /sites,               VIEWER",
            "GET,  /sites/new,           VIEWER",
            "POST, /sites/new,           EDITOR",
            "POST, /sites/1,             EDITOR",
            "POST, /sites/1/enabled,     EDITOR",
            "GET,  /files,               VIEWER",
            "POST, /files/1,             EDITOR",
            "GET,  /apply,               EDITOR",
            "POST, /apply,               EDITOR",
            "GET,  /versions,            VIEWER",
            "POST, /versions/1/rollback, EDITOR",
            "POST, /drift/import,        EDITOR",
            "GET,  /users,               ADMIN",
            "POST, /users,               ADMIN",
            "POST, /users/1/role,        ADMIN",
            "GET,  /audit,               ADMIN",
            "GET,  /certs,               VIEWER",
            "POST, /certs/1/renew,       EDITOR",
            "POST, /certs/upload,        EDITOR",
            "POST, /certs/letsencrypt,   EDITOR",
            "GET,  /settings/acme,       ADMIN",
            "POST, /settings/acme,       ADMIN",
            "GET,  /settings/notifications, ADMIN",
            "POST, /settings/notifications, ADMIN",
            "GET,  /logs/1,              VIEWER",
            "POST, /runtime/start,       ADMIN",
            "GET,  /account,             VIEWER",
            "POST, /account,             VIEWER",
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
