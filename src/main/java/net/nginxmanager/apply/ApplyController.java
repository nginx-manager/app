package net.nginxmanager.apply;

import net.nginxmanager.config.ConfigFile;
import net.nginxmanager.config.ConfigFileRepository;
import net.nginxmanager.config.ConfigFileStore;
import net.nginxmanager.config.UnifiedDiff;
import net.nginxmanager.runtime.NginxCommandException;
import net.nginxmanager.runtime.RuntimeCompatibility;
import net.nginxmanager.common.Messages;
import net.nginxmanager.common.NotFoundException;
import net.nginxmanager.site.SiteAccess;
import net.nginxmanager.site.SiteService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.nio.file.AccessDeniedException;
import java.util.List;
import java.util.function.Predicate;

@Controller
@RequestMapping("/apply")
public class ApplyController {

    public record PendingFile(Long id, String path, boolean delete, String diff) {

        /** 더한 줄 수. 파일 머리(+++)는 세지 않는다 */
        public long added() {
            return diff.lines().filter(line -> line.startsWith("+") && !line.startsWith("+++")).count();
        }

        /** 뺀 줄 수. 파일 머리(---)는 세지 않는다 */
        public long removed() {
            return diff.lines().filter(line -> line.startsWith("-") && !line.startsWith("---")).count();
        }
    }

    private static final Logger log = LoggerFactory.getLogger(ApplyController.class);

    private final ApplyService applyService;
    private final ConfigFileRepository configFileRepository;
    private final ConfigFileStore store;
    private final SiteService siteService;
    private final RuntimeCompatibility compatibility;
    private final SiteAccess access;
    private final Messages messages;

    public ApplyController(ApplyService applyService, ConfigFileRepository configFileRepository,
                           ConfigFileStore store, SiteService siteService, RuntimeCompatibility compatibility,
                           SiteAccess access, Messages messages) {
        this.access = access;
        this.messages = messages;
        this.applyService = applyService;
        this.configFileRepository = configFileRepository;
        this.store = store;
        this.siteService = siteService;
        this.compatibility = compatibility;
    }

    @GetMapping
    public String pending(Authentication authentication, Model model) {
        model.addAttribute("pending", pendingFiles(authentication));
        model.addAttribute("applyBlocked", applyBlocked());
        return "apply/pending";
    }

    @PostMapping
    public String apply(@RequestParam(defaultValue = "") String message, Authentication authentication,
                        @RequestHeader(value = "HX-Request", required = false) String htmx, Model model) {
        try {
            model.addAttribute("result",
                    applyService.applyPending(authentication.getName(), message.isBlank() ? null : message,
                            access.files(authentication)));
        } catch (NginxCommandException e) {
            // 에이전트에 연결할 수 없거나 명령이 실패했다. 파일은 되돌렸고 초안은 그대로다
            model.addAttribute("failure", e.getMessage());
        } catch (ApplyInProgressException e) {
            throw e;
        } catch (RuntimeException e) {
            // 예상하지 못한 오류도 화면에 이유를 보인다(아무 반응 없이 500으로 끝나지 않게)
            log.error("apply failed", e);
            model.addAttribute("failure", failureText(e));
        }
        List<PendingFile> pending = pendingFiles(authentication);
        model.addAttribute("pending", pending);
        // 메뉴 숫자는 적용 전에 셌으므로 적용한 뒤의 수로 바꾼다
        model.addAttribute("pendingCount", (long) pending.size());
        model.addAttribute("applyBlocked", applyBlocked());
        model.addAttribute("htmx", htmx != null);
        return htmx != null ? "apply/pending :: area" : "apply/pending";
    }

    /** 설정 디렉터리에 쓰지 못한 경우(서버에서 sudo로 만든 root 소유 디렉터리 등)는 고치는 방법을 함께 보인다. */
    private String failureText(RuntimeException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof AccessDeniedException denied) {
                return messages.get("apply.failure.denied", denied.getFile());
            }
        }
        return e.getClass().getSimpleName() + ": " + e.getMessage();
    }

    @PostMapping("/discard/{fileId}")
    public String discard(@PathVariable long fileId, Authentication authentication) {
        // 멤버는 담당 사이트의 초안만 버린다. 짝 파일(켜기/끄기, 비밀번호, 가져오기 원본)도 같은 사이트의 것이다
        boolean mine = configFileRepository.findById(fileId)
                .map(f -> access.files(authentication).test(f.getPath()))
                .orElse(false);
        if (!mine) {
            throw new NotFoundException("config file " + fileId);
        }
        siteService.discard(fileId, authentication.getName());
        return "redirect:/apply";
    }

    /** 에이전트에 연결할 수 없거나 API 판이 다르면 적용 버튼을 감춘다. 릴리스 판만 다르면 적용은 된다. */
    private boolean applyBlocked() {
        return compatibility.current().isPresent();
    }

    private List<PendingFile> pendingFiles(Authentication authentication) {
        Predicate<String> visible = access.files(authentication);
        return configFileRepository.findAllByOrderByPathAsc().stream()
                .filter(ConfigFile::hasPendingChange)
                .filter(f -> visible.test(f.getPath()))
                .map(f -> new PendingFile(f.getId(), f.getPath(), f.isDraftDelete(),
                        UnifiedDiff.of(f.getPath(), store.read(f.getPath()), f.isDraftDelete() ? null : f.getDraftContent())))
                .toList();
    }
}
