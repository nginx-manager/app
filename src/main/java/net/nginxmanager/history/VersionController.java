package net.nginxmanager.history;

import net.nginxmanager.apply.ApplyResult;
import net.nginxmanager.common.NotFoundException;
import net.nginxmanager.config.ConfigVersion;
import net.nginxmanager.config.ConfigVersionFile;
import net.nginxmanager.runtime.NginxCommandException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Comparator;
import java.util.List;

@Controller
public class VersionController {

    static final int PAGE_SIZE = 30;

    private final VersionQueryRepository versionQueryRepository;
    private final RollbackService rollbackService;
    private final DriftDetector driftDetector;

    public VersionController(VersionQueryRepository versionQueryRepository, RollbackService rollbackService,
                             DriftDetector driftDetector) {
        this.versionQueryRepository = versionQueryRepository;
        this.rollbackService = rollbackService;
        this.driftDetector = driftDetector;
    }

    @GetMapping("/versions")
    public String list(@RequestParam(defaultValue = "0") int page, Model model) {
        int safePage = Math.max(page, 0);
        List<VersionQueryRepository.VersionRow> rows =
                versionQueryRepository.findPage(safePage * PAGE_SIZE, PAGE_SIZE + 1);
        model.addAttribute("versions", rows.stream().limit(PAGE_SIZE).toList());
        model.addAttribute("page", safePage);
        model.addAttribute("hasNext", rows.size() > PAGE_SIZE);
        // 이력 화면에서는 항상 새로 확인한다
        model.addAttribute("drift", driftDetector.refresh());
        return "versions/list";
    }

    @GetMapping("/versions/{id}")
    public String detail(@PathVariable long id, Model model) {
        ConfigVersion version = load(id);
        model.addAttribute("version", version);
        model.addAttribute("files", version.getFiles().stream()
                .sorted(Comparator.comparing(ConfigVersionFile::getPath)).toList());
        return "versions/detail";
    }

    @GetMapping("/versions/compare")
    public String compare(@RequestParam long a, @RequestParam long b) {
        return "redirect:/versions/" + Math.min(a, b) + "/diff/" + Math.max(a, b);
    }

    @GetMapping("/versions/{a}/diff/{b}")
    public String diff(@PathVariable long a, @PathVariable long b, Model model) {
        ConfigVersion from = load(a);
        ConfigVersion to = load(b);
        model.addAttribute("from", from);
        model.addAttribute("to", to);
        model.addAttribute("diffs", VersionDiff.between(from, to));
        return "versions/diff";
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
