package net.nginxmanager.guide;

import net.nginxmanager.support.ConfigRootTest;
import net.nginxmanager.user.Role;
import net.nginxmanager.user.SetupState;
import net.nginxmanager.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GuideWebTest extends ConfigRootTest {

    private static final Pattern HREF = Pattern.compile("(?:href|src)=\"([^\"]*)\"");

    @Autowired
    MockMvc mvc;
    @Autowired
    SetupState setupState;
    @Autowired
    Guide guide;

    @BeforeEach
    void completeSetup() {
        userRepository.save(new User("admin", "{noop}unused-password", Role.ADMIN));
        setupState.reset();
    }

    @Test
    void indexShowsTableOfContentsAndFirstChapterLink() throws Exception {
        mvc.perform(get("/guide").with(user("v").roles("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<h1 id=\"nginx-manager-사용-설명서\">nginx manager 사용 설명서</h1>")))
                .andExpect(content().string(containsString("class=\"nav-manual active\" href=\"/guide\"")))
                .andExpect(content().string(containsString("<a href=\"/guide/01-install\">1. 설치와 실행</a>")))
                .andExpect(content().string(containsString("<a href=\"/guide/13-troubleshooting\">13. 문제 해결</a>")))
                .andExpect(content().string(containsString("class=\"next\" href=\"/guide/01-install\"")))
                .andExpect(content().string(not(containsString("class=\"prev\""))))
                .andExpect(content().string(not(containsString("guide-lang"))));
    }

    @Test
    void chapterShowsBodySectionsAndNeighbours() throws Exception {
        mvc.perform(get("/guide/04-sites").with(user("v").roles("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<title>4. 사이트 · 사용 설명서 · nginx manager</title>")))
                .andExpect(content().string(containsString("href=\"/guide/04-sites\" aria-current=\"page\" class=\"active\">4. 사이트</a>")))
                .andExpect(content().string(containsString("<img src=\"/guide/images/sites.png\"")))
                .andExpect(content().string(containsString("class=\"guide-onpage\"")))
                .andExpect(content().string(containsString("class=\"prev\" href=\"/guide/03-dashboard\"")))
                .andExpect(content().string(containsString("class=\"next\" href=\"/guide/05-import\"")));
    }

    @Test
    void otherLanguagesShowKoreanTextWithNotice() throws Exception {
        mvc.perform(get("/guide/01-install").param("lang", "en").with(user("v").roles("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("The manual text is available in Korean only.")))
                .andExpect(content().string(containsString(">Contents</summary>")))
                .andExpect(content().string(containsString("1. 설치와 실행")));
    }

    @Test
    void unknownChapterIsNotFound() throws Exception {
        mvc.perform(get("/guide/99-nothing").with(user("v").roles("VIEWER")))
                .andExpect(status().isNotFound());
    }

    @Test
    void guideNeedsLogin() throws Exception {
        mvc.perform(get("/guide"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    /** 모든 장의 장 링크·소제목 링크·그림이 실제로 있는지. 원문을 고치다 링크가 깨지면 여기서 걸린다. */
    @Test
    void everyLocalLinkAnchorAndImageResolves() throws Exception {
        Guide.Book book = guide.book(Locale.KOREAN);
        List<Guide.Chapter> all = new ArrayList<>(book.chapters());
        all.add(book.index());
        assertThat(book.chapters()).hasSize(13);
        List<String> broken = new ArrayList<>();
        for (Guide.Chapter chapter : all) {
            mvc.perform(get(guide.url(chapter.slug())).with(user("v").roles("VIEWER"))).andExpect(status().isOk());
            Matcher link = HREF.matcher(chapter.html());
            while (link.find()) {
                String href = link.group(1);
                if (href.startsWith("http")) {
                    continue;
                }
                if (href.startsWith("/guide/images/")) {
                    if (!new ClassPathResource("static" + href).exists()) {
                        broken.add(chapter.slug() + ": " + href);
                    }
                    continue;
                }
                String path = href.contains("#") ? href.substring(0, href.indexOf('#')) : href;
                String fragment = href.contains("#") ? href.substring(href.indexOf('#') + 1) : null;
                Guide.Chapter target = path.isEmpty() ? chapter
                        : path.equals("/guide") ? book.index()
                        : path.startsWith("/guide/") ? book.chapter(path.substring("/guide/".length())).orElse(null)
                        : null;
                if (target == null || (fragment != null && !target.ids().contains(fragment))) {
                    broken.add(chapter.slug() + ": " + href);
                }
            }
        }
        assertThat(broken).isEmpty();
    }
}
