package net.nginxmanager.apply;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import net.nginxmanager.config.ConfigFile;
import net.nginxmanager.config.ConfigFileRepository;
import net.nginxmanager.config.ConfigFileStore;
import net.nginxmanager.site.SiteService;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@Controller
@RequestMapping("/apply")
public class ApplyController {

    public record PendingFile(Long id, String path, boolean delete, String diff) {
    }

    private final ApplyService applyService;
    private final ConfigFileRepository configFileRepository;
    private final ConfigFileStore store;
    private final SiteService siteService;

    public ApplyController(ApplyService applyService, ConfigFileRepository configFileRepository,
                           ConfigFileStore store, SiteService siteService) {
        this.applyService = applyService;
        this.configFileRepository = configFileRepository;
        this.store = store;
        this.siteService = siteService;
    }

    @GetMapping
    public String pending(Model model) {
        model.addAttribute("pending", pendingFiles());
        return "apply/pending";
    }

    @PostMapping
    public String apply(@RequestParam(defaultValue = "") String message, Authentication authentication,
                        @RequestHeader(value = "HX-Request", required = false) String htmx, Model model) {
        ApplyResult result = applyService.applyPending(authentication.getName(), message.isBlank() ? null : message);
        model.addAttribute("result", result);
        model.addAttribute("pending", pendingFiles());
        return htmx != null ? "apply/pending :: result" : "apply/pending";
    }

    @PostMapping("/discard/{fileId}")
    public String discard(@PathVariable long fileId, Authentication authentication) {
        siteService.discard(fileId, authentication.getName());
        return "redirect:/apply";
    }

    private List<PendingFile> pendingFiles() {
        return configFileRepository.findAllByOrderByPathAsc().stream()
                .filter(ConfigFile::hasPendingChange)
                .map(f -> new PendingFile(f.getId(), f.getPath(), f.isDraftDelete(),
                        diff(f.getPath(), store.read(f.getPath()), f.isDraftDelete() ? null : f.getDraftContent())))
                .toList();
    }

    static String diff(String path, String before, String after) {
        List<String> original = lines(before);
        List<String> revised = lines(after);
        List<String> unified = UnifiedDiffUtils.generateUnifiedDiff(
                before == null ? "/dev/null" : "a/" + path,
                after == null ? "/dev/null" : "b/" + path,
                original, DiffUtils.diff(original, revised), 3);
        return String.join("\n", unified);
    }

    private static List<String> lines(String content) {
        return content == null ? List.of() : content.lines().toList();
    }
}
