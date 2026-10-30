package net.nginxmanager.history;

import net.nginxmanager.apply.ApplyResult;
import net.nginxmanager.common.NotFoundException;
import net.nginxmanager.config.ConfigVersion;
import net.nginxmanager.config.ConfigVersionFile;
import net.nginxmanager.runtime.NginxCommandException;
import net.nginxmanager.site.Htpasswd;
import net.nginxmanager.site.SiteAccess;
import net.nginxmanager.site.SitePaths;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.stream.Stream;

@Controller
public class VersionController {

    static final int PAGE_SIZE = 30;

    private final VersionQueryRepository versionQueryRepository;
    private final RollbackService rollbackService;
    private final DriftDetector driftDetector;
    private final SiteAccess access;

    public VersionController(VersionQueryRepository versionQueryRepository, RollbackService rollbackService,
                             DriftDetector driftDetector, SiteAccess access) {
        this.access = access;
        this.versionQueryRepository = versionQueryRepository;
        this.rollbackService = rollbackService;
        this.driftDetector = driftDetector;
    }

    /**
     * 버전 목록. domain을 주면 그 사이트 파일이 바뀐 버전만 보인다(014). 멤버는 담당 사이트의 domain이 있어야 한다.
     */
    @GetMapping("/versions")
    public String list(@RequestParam(defaultValue = "0") int page, @RequestParam(required = false) String domain,
                       Authentication authentication, Model model) {
        int safePage = Math.max(page, 0);
        String site = normalize(domain);
        boolean admin = SiteAccess.isAdmin(authentication);
        if (!admin && (site == null || !access.files(authentication).test(SitePaths.of(site, true)))) {
            throw new NotFoundException("versions of " + domain);
        }
        List<VersionQueryRepository.VersionRow> rows;
        if (site == null) {
            rows = versionQueryRepository.findPage(safePage * PAGE_SIZE, PAGE_SIZE + 1);
        } else {
            List<Long> ids = versionQueryRepository.versionsTouching(sitePaths(site));
            rows = versionQueryRepository.findRows(ids.stream().skip((long) safePage * PAGE_SIZE)
                    .limit(PAGE_SIZE + 1).toList());
        }
        model.addAttribute("versions", rows.stream().limit(PAGE_SIZE).toList());
        model.addAttribute("page", safePage);
        model.addAttribute("hasNext", rows.size() > PAGE_SIZE);
        model.addAttribute("domain", site);
        // 이력 화면에서는 항상 새로 확인한다. 서버 전체의 일이라 관리자에게만 보인다
        model.addAttribute("drift", admin ? driftDetector.refresh() : List.of());
        return "versions/list";
    }

    @GetMapping("/versions/{id}")
    public String detail(@PathVariable long id, @RequestParam(required = false) String domain,
                         Authentication authentication, Model model) {
        ConfigVersion version = load(id);
        Predicate<String> visible = visible(domain, authentication);
        List<ConfigVersionFile> files = version.getFiles().stream()
                .filter(f -> visible.test(f.getPath()))
                .sorted(Comparator.comparing(ConfigVersionFile::getPath)).toList();
        if (files.isEmpty() && !SiteAccess.isAdmin(authentication)) {
            throw new NotFoundException("version " + id);
        }
        model.addAttribute("version", version);
        model.addAttribute("files", files);
        model.addAttribute("domain", normalize(domain));
        return "versions/detail";
    }

    @GetMapping("/versions/compare")
    public String compare(@RequestParam long a, @RequestParam long b, @RequestParam(required = false) String domain) {
        String site = normalize(domain);
        return "redirect:/versions/" + Math.min(a, b) + "/diff/" + Math.max(a, b)
                + (site == null ? "" : "?domain=" + site);
    }

    @GetMapping("/versions/{a}/diff/{b}")
    public String diff(@PathVariable long a, @PathVariable long b, @RequestParam(required = false) String domain,
                       Authentication authentication, Model model) {
        ConfigVersion from = load(a);
        ConfigVersion to = load(b);
        Predicate<String> visible = visible(domain, authentication);
        if (!SiteAccess.isAdmin(authentication)
                && Stream.concat(from.getFiles().stream(), to.getFiles().stream()).noneMatch(f -> visible.test(f.getPath()))) {
            throw new NotFoundException("version " + a + ".." + b);
        }
        model.addAttribute("from", from);
        model.addAttribute("to", to);
        model.addAttribute("diffs", VersionDiff.between(from, to).stream().filter(d -> visible.test(d.path())).toList());
        model.addAttribute("domain", normalize(domain));
        return "versions/diff";
    }

    /** 화면에 보일 파일: domain을 주면 그 사이트 파일, 멤버는 담당 사이트 파일만. */
    private Predicate<String> visible(String domain, Authentication authentication) {
        Predicate<String> allowed = access.files(authentication);
        String site = normalize(domain);
        if (site == null) {
            return allowed;
        }
        List<String> paths = sitePaths(site);
        return allowed.and(paths::contains);
    }

    /** 사이트 파일(켜짐·꺼짐)과 비밀번호 파일. 도메인으로 정해지므로 지운 사이트도 찾는다. */
    private static List<String> sitePaths(String domain) {
        return List.of(SitePaths.of(domain, true), SitePaths.of(domain, false), Htpasswd.path(domain));
    }

    private static String normalize(String domain) {
        if (domain == null || domain.isBlank()) {
            return null;
        }
        String site = domain.strip().toLowerCase(Locale.ROOT);
        if (!site.matches("[a-z0-9*._-]+")) {
            throw new NotFoundException("site " + domain);
        }
        return site;
    }

    @PostMapping("/versions/{id}/rollback")
    public String rollback(@PathVariable long id, Authentication authentication) {
        try {
            ApplyResult result = rollbackService.rollback(id, authentication.getName());
            if (result.nothingChanged()) {
                return "redirect:/versions/" + id + "?unchanged";
            }
            return "redirect:/versions/" + result.versionId();
        } catch (RollbackRejectedException e) {
            return "redirect:/versions/" + id + "?rejected=" + e.getReason();
        } catch (NginxCommandException e) {
            return "redirect:/versions/" + id + "?rejected=UNREACHABLE";
        }
    }

    @PostMapping("/drift/import")
    public String importDrift(Authentication authentication) {
        Long versionId = driftDetector.importDrift(authentication.getName());
        return versionId == null ? "redirect:/versions" : "redirect:/versions/" + versionId;
    }

    private ConfigVersion load(long id) {
        return versionQueryRepository.findWithFiles(id).orElseThrow(() -> new NotFoundException("version " + id));
    }
}
