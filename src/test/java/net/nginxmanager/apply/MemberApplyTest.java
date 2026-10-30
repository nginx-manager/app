package net.nginxmanager.apply;

import net.nginxmanager.site.Site;
import net.nginxmanager.site.SiteForm;
import net.nginxmanager.site.SiteService;
import net.nginxmanager.site.SiteType;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 007 US2: 멤버의 적용은 본인 사이트의 초안만 반영하고, 다른 사람의 초안은 그대로 둔다. */
class MemberApplyTest extends ConfigRootTest {

    static final String ALICE = "conf.d/sites/alice.local.conf";
    static final String BOB = "conf.d/sites/bob.local.conf";

    @Autowired
    MockMvc mvc;
    @Autowired
    SetupState setupState;
    @Autowired
    SiteService siteService;

    Site aliceSite;

    @BeforeEach
    void setUp() {
        userRepository.save(new User("admin", "{noop}unused-password", Role.ADMIN));
        User alice = userRepository.save(new User("alice", "{noop}unused-password", Role.MEMBER));
        User bob = userRepository.save(new User("bob", "{noop}unused-password", Role.MEMBER));
        setupState.reset();
        aliceSite = siteService.create(staticForm("alice.local"), "alice", alice.getId());
        siteService.create(staticForm("bob.local"), "bob", bob.getId());
    }

    private static SiteForm staticForm(String domain) {
        return new SiteForm(domain, "", SiteType.STATIC, 80, "/srv/www/" + domain, null, null);
    }

    @Test
    void memberSeesAndAppliesOnlyTheirDrafts() throws Exception {
        mvc.perform(get("/apply").with(user("alice").roles("MEMBER")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(ALICE)))
                .andExpect(content().string(not(containsString(BOB))))
                .andExpect(content().string(containsString("내가 담당한 사이트의 변경만")));
        mvc.perform(get("/sites").with(user("alice").roles("MEMBER")))
                .andExpect(content().string(containsString("<span class=\"count\">1</span>")));

        mvc.perform(post("/apply").with(csrf()).with(user("alice").roles("MEMBER")).header("HX-Request", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("모두 통과했습니다")));

        assertThat(readFile(ALICE)).contains("root /srv/www/alice.local;");
        assertThat(readFile(BOB)).as("bob의 초안은 반영되지 않는다").isNull();
        assertThat(configFileRepository.findByPath(BOB).orElseThrow().hasPendingChange()).isTrue();
        assertThat(configFileRepository.findByPath(ALICE).orElseThrow().hasPendingChange()).isFalse();
        assertThat(configVersionRepository.findFirstByOrderByIdDesc()).get()
                .satisfies(v -> assertThat(v.getAuthorName()).isEqualTo("alice"));

        // 관리자는 남은 초안을 모두 반영한다
        mvc.perform(post("/apply").with(csrf()).with(user("admin").roles("ADMIN")).header("HX-Request", "true"))
                .andExpect(content().string(containsString("모두 통과했습니다")));
        assertThat(readFile(BOB)).contains("root /srv/www/bob.local;");
    }

    @Test
    void enableToggleMovesBothFilesTogether() throws Exception {
        mvc.perform(post("/apply").with(csrf()).with(user("alice").roles("MEMBER")).header("HX-Request", "true"))
                .andExpect(content().string(containsString("모두 통과했습니다")));
        Site loaded = siteService.setEnabled(aliceSite.getId(), false,
                siteRepository.findById(aliceSite.getId()).orElseThrow().getVersion(), "alice");

        mvc.perform(post("/apply").with(csrf()).with(user("alice").roles("MEMBER")).header("HX-Request", "true"))
                .andExpect(content().string(containsString("모두 통과했습니다")));
        assertThat(readFile(ALICE)).isNull();
        assertThat(readFile("sites-disabled/alice.local.conf")).contains("alice.local");
        assertThat(loaded.isEnabled()).isFalse();
    }

    @Test
    void memberCannotDiscardOthersDrafts() throws Exception {
        long bobFile = configFileRepository.findByPath(BOB).orElseThrow().getId();
        mvc.perform(post("/apply/discard/" + bobFile).with(csrf()).with(user("alice").roles("MEMBER")))
                .andExpect(status().isNotFound());
        assertThat(configFileRepository.findByPath(BOB).orElseThrow().hasPendingChange()).isTrue();

        long aliceFile = configFileRepository.findByPath(ALICE).orElseThrow().getId();
        mvc.perform(post("/apply/discard/" + aliceFile).with(csrf()).with(user("alice").roles("MEMBER")))
                .andExpect(status().is3xxRedirection());
        assertThat(siteRepository.findByPrimaryDomain("alice.local")).as("한 번도 적용하지 않은 새 사이트는 사라진다").isEmpty();
    }
}
