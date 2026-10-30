package net.nginxmanager.site;

import net.nginxmanager.audit.AuditAction;
import net.nginxmanager.support.ConfigRootTest;
import net.nginxmanager.user.Role;
import net.nginxmanager.user.SetupState;
import net.nginxmanager.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 007 US1·US3: 멤버는 담당 사이트만 보고 고치고, 관리자는 담당자를 정한다. */
class SiteOwnershipWebTest extends ConfigRootTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    SetupState setupState;
    @Autowired
    SiteService siteService;

    User alice;
    User bob;
    Site aliceSite;
    Site bobSite;
    Site unassigned;

    @BeforeEach
    void setUp() {
        userRepository.save(new User("admin", "{noop}unused-password", Role.ADMIN));
        alice = userRepository.save(new User("alice", "{noop}unused-password", Role.MEMBER));
        bob = userRepository.save(new User("bob", "{noop}unused-password", Role.MEMBER));
        setupState.reset();
        aliceSite = siteService.create(staticForm("alice.local"), "alice", alice.getId());
        bobSite = siteService.create(staticForm("bob.local"), "bob", bob.getId());
        unassigned = siteService.create(staticForm("none.local"), "admin");
    }

    private static SiteForm staticForm(String domain) {
        return new SiteForm(domain, "", SiteType.STATIC, 80, "/srv/www/" + domain, null, null);
    }

    @Test
    void memberListsOnlyTheirSitesAndAdminSeesOwners() throws Exception {
        mvc.perform(get("/sites").with(user("alice").roles("MEMBER")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("alice.local")))
                .andExpect(content().string(not(containsString("bob.local"))))
                .andExpect(content().string(not(containsString("none.local"))))
                .andExpect(content().string(containsString("내가 담당한 사이트만 보입니다")));

        mvc.perform(get("/sites").with(user("admin").roles("ADMIN")))
                .andExpect(content().string(containsString("alice.local")))
                .andExpect(content().string(containsString("bob.local")))
                .andExpect(content().string(containsString("none.local")))
                .andExpect(content().string(containsString("<span>alice</span>")));
        mvc.perform(get("/sites").param("owner", "none").with(user("admin").roles("ADMIN")))
                .andExpect(content().string(containsString("none.local")))
                .andExpect(content().string(not(containsString("alice.local"))));
        mvc.perform(get("/sites").param("owner", String.valueOf(bob.getId())).with(user("admin").roles("ADMIN")))
                .andExpect(content().string(containsString("bob.local")))
                .andExpect(content().string(not(containsString("alice.local"))));
    }

    @Test
    void memberCannotReachOtherSites() throws Exception {
        for (Site other : new Site[] {bobSite, unassigned}) {
            long version = siteRepository.findById(other.getId()).orElseThrow().getVersion();
            mvc.perform(get("/sites/" + other.getId()).with(user("alice").roles("MEMBER")))
                    .andExpect(status().isNotFound());
            mvc.perform(post("/sites/" + other.getId()).with(csrf()).with(user("alice").roles("MEMBER"))
                            .param("primaryDomain", other.getPrimaryDomain()).param("type", "STATIC")
                            .param("listenHttpPort", "80").param("staticRoot", "/hacked")
                            .param("version", String.valueOf(version)))
                    .andExpect(status().isNotFound());
            mvc.perform(post("/sites/" + other.getId() + "/enabled").with(csrf()).with(user("alice").roles("MEMBER"))
                            .param("enabled", "false").param("version", String.valueOf(version)))
                    .andExpect(status().isNotFound());
            mvc.perform(post("/certs/letsencrypt").with(csrf()).with(user("alice").roles("MEMBER"))
                            .param("siteId", String.valueOf(other.getId())))
                    .andExpect(status().isNotFound());
        }
        assertThat(siteRepository.findById(bobSite.getId()).orElseThrow().getStaticRoot()).isEqualTo("/srv/www/bob.local");
        assertThat(siteRepository.findById(bobSite.getId()).orElseThrow().isEnabled()).isTrue();

        mvc.perform(get("/sites/" + aliceSite.getId()).with(user("alice").roles("MEMBER")))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("담당자 저장"))))
                .andExpect(content().string(not(containsString("Raw 편집</a>"))));
    }

    @Test
    void memberCreatesSitesTheyOwn() throws Exception {
        mvc.perform(post("/sites/new").with(csrf()).with(user("alice").roles("MEMBER"))
                        .param("primaryDomain", "new.alice.local").param("extraDomains", "").param("type", "STATIC")
                        .param("listenHttpPort", "80").param("staticRoot", "/srv/www/new"))
                .andExpect(redirectedUrlPattern("/sites/*?saved"));
        assertThat(siteRepository.findByPrimaryDomain("new.alice.local").orElseThrow().getOwnerId())
                .isEqualTo(alice.getId());

        // 다른 사람의 도메인은 누구의 사이트인지 알리지 않고 거절한다
        mvc.perform(post("/sites/new").with(csrf()).with(user("alice").roles("MEMBER"))
                        .param("primaryDomain", "bob.local").param("extraDomains", "").param("type", "STATIC")
                        .param("listenHttpPort", "80").param("staticRoot", "/srv/www/x"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("이미 다른 사이트가 쓰는 도메인")));
    }

    @Test
    void adminAssignsOwnersAndItIsAudited() throws Exception {
        long version = siteRepository.findById(unassigned.getId()).orElseThrow().getVersion();
        mvc.perform(get("/sites/" + unassigned.getId()).with(user("admin").roles("ADMIN")))
                .andExpect(content().string(containsString("담당자 저장")));
        mvc.perform(post("/sites/" + unassigned.getId() + "/owner").with(csrf()).with(user("admin").roles("ADMIN"))
                        .param("owner", String.valueOf(alice.getId())).param("version", String.valueOf(version)))
                .andExpect(redirectedUrlPattern("/sites/*?assigned"));

        assertThat(siteRepository.findById(unassigned.getId()).orElseThrow().getOwnerId()).isEqualTo(alice.getId());
        assertThat(configFileRepository.findByPath("conf.d/sites/none.local.conf").orElseThrow().getDraftContent())
                .as("담당자 변경은 설정 파일을 바꾸지 않는다").isEqualTo(unassigned.getConfigFile().getDraftContent());
        mvc.perform(get("/sites/" + unassigned.getId()).with(user("alice").roles("MEMBER")))
                .andExpect(status().isOk());
        assertThat(auditLogRepository.findAll()).anySatisfy(log -> {
            assertThat(log.getAction()).isEqualTo(AuditAction.SITE_ASSIGN);
            assertThat(log.getTarget()).isEqualTo("site:none.local owner:-->alice");
        });

        // 해제하면 다시 관리자만 본다
        version = siteRepository.findById(unassigned.getId()).orElseThrow().getVersion();
        mvc.perform(post("/sites/" + unassigned.getId() + "/owner").with(csrf()).with(user("admin").roles("ADMIN"))
                        .param("owner", "").param("version", String.valueOf(version)))
                .andExpect(redirectedUrlPattern("/sites/*?assigned"));
        mvc.perform(get("/sites/" + unassigned.getId()).with(user("alice").roles("MEMBER")))
                .andExpect(status().isNotFound());

        mvc.perform(post("/sites/" + aliceSite.getId() + "/owner").with(csrf()).with(user("alice").roles("MEMBER"))
                        .param("owner", String.valueOf(alice.getId())).param("version", "0"))
                .andExpect(status().isForbidden());
    }

    @Test
    void memberDashboardShowsOnlyTheirCountsAndNoAdminBlocks() throws Exception {
        mvc.perform(get("/").with(user("alice").roles("MEMBER")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("1 활성 / 1 전체")))
                .andExpect(content().string(not(containsString("최근 적용"))))
                .andExpect(content().string(not(containsString("DB 크기"))))
                .andExpect(content().string(not(containsString("href=\"/files\""))))
                .andExpect(content().string(not(containsString("href=\"/versions\""))));
        mvc.perform(get("/").with(user("admin").roles("ADMIN")))
                .andExpect(content().string(containsString("3 활성 / 3 전체")))
                .andExpect(content().string(containsString("최근 적용")));
    }

    @Test
    void usersPageShowsAssignedSiteCounts() throws Exception {
        mvc.perform(get("/users").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/sites?owner=" + alice.getId() + "\">1</a>")))
                .andExpect(content().string(containsString("멤버: 담당 사이트만")))
                .andExpect(content().string(not(containsString("편집자"))));
    }
}
