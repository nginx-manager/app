package net.nginxmanager.api;

import net.nginxmanager.apply.ApplyInProgressException;
import net.nginxmanager.apply.ApplyResult;
import net.nginxmanager.apply.ApplyService;
import net.nginxmanager.common.ConcurrentEditException;
import net.nginxmanager.common.NotFoundException;
import net.nginxmanager.runtime.NginxCommandException;
import net.nginxmanager.runtime.RuntimeCompatibility;
import net.nginxmanager.site.RawSiteFormException;
import net.nginxmanager.site.SiteQueryRepository;
import net.nginxmanager.site.SiteQueryRepository.SiteRow;
import net.nginxmanager.site.SiteService;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 배포 자동화 API(033). 토큰으로만 부르고(관리자 권한), 감사 로그에는 token:이름으로 남는다.
 */
@RestController
@RequestMapping("/api/v1")
public class ApiController {

    /** 적용 이력에 남는 메모. */
    private static final String APPLY_MESSAGE = "API";

    private final SiteQueryRepository siteQueryRepository;
    private final SiteService siteService;
    private final ApplyService applyService;
    private final RuntimeCompatibility compatibility;

    public ApiController(SiteQueryRepository siteQueryRepository, SiteService siteService, ApplyService applyService,
                         RuntimeCompatibility compatibility) {
        this.siteQueryRepository = siteQueryRepository;
        this.siteService = siteService;
        this.applyService = applyService;
        this.compatibility = compatibility;
    }

    public record SiteView(String domain, String type, boolean enabled, boolean maintenance, boolean pending,
                           boolean https) {

        static SiteView of(SiteRow row) {
            return new SiteView(row.primaryDomain(), row.type().name(), row.enabled(), row.maintenance(),
                    row.pending(), row.httpsEnabled());
        }
    }

    /** @param apply true면 바꾼 뒤 적용 대기 중인 변경을 모두 적용한다 */
    public record MaintenanceRequest(Boolean enabled, Boolean apply) {
    }

    public record ApplyView(String result, Long versionId, List<String> changedPaths, List<String> errors,
                            String detail) {

        static ApplyView of(ApplyResult result) {
            return new ApplyView(result.nothingChanged() ? "NOTHING_TO_APPLY" : result.result().name(),
                    result.versionId(), result.changedPaths(),
                    result.messages().stream().map(m -> m.file() == null ? m.message()
                            : m.message() + " (" + m.file() + (m.line() == null ? "" : ":" + m.line()) + ")").toList(),
                    result.detail());
        }

        boolean failed() {
            return !"NOTHING_TO_APPLY".equals(result) && !"SUCCESS".equals(result);
        }
    }

    public record MaintenanceView(String domain, boolean maintenance, boolean changed, ApplyView apply) {
    }

    @GetMapping("/sites")
    public List<SiteView> sites() {
        return siteQueryRepository.findAllRows().stream().map(SiteView::of).toList();
    }

    @PostMapping("/sites/{domain}/maintenance")
    public ResponseEntity<?> maintenance(@PathVariable String domain, @RequestBody MaintenanceRequest request,
                                         Authentication authentication) {
        if (request == null || request.enabled() == null) {
            return error(HttpStatus.BAD_REQUEST, "\"enabled\" (true or false) is required");
        }
        SiteRow row = siteQueryRepository.findAllRows().stream()
                .filter(r -> r.primaryDomain().equalsIgnoreCase(domain))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("site " + domain));
        boolean changed = siteService.setMaintenance(row.id(), request.enabled(), authentication.getName());
        ApplyView applied = null;
        if (Boolean.TRUE.equals(request.apply())) {
            ResponseEntity<?> blocked = blocked();
            if (blocked != null) {
                return blocked;
            }
            applied = ApplyView.of(applyService.applyPending(authentication.getName(), APPLY_MESSAGE));
        }
        MaintenanceView body = new MaintenanceView(row.primaryDomain(), request.enabled(), changed, applied);
        return ResponseEntity.status(applied != null && applied.failed() ? HttpStatus.UNPROCESSABLE_ENTITY : HttpStatus.OK)
                .body(body);
    }

    @PostMapping("/apply")
    public ResponseEntity<?> apply(Authentication authentication) {
        ResponseEntity<?> blocked = blocked();
        if (blocked != null) {
            return blocked;
        }
        ApplyView applied = ApplyView.of(applyService.applyPending(authentication.getName(), APPLY_MESSAGE));
        return ResponseEntity.status(applied.failed() ? HttpStatus.UNPROCESSABLE_ENTITY : HttpStatus.OK).body(applied);
    }

    /** 관리도구와 nginx 컨테이너 판이 맞지 않으면 화면처럼 적용을 막는다. */
    private ResponseEntity<?> blocked() {
        return compatibility.current().isPresent()
                ? error(HttpStatus.CONFLICT, "apply is blocked until the nginx container is upgraded")
                : null;
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }

    /** 본문이 없거나 JSON이 아니면 다른 오류처럼 {"error": …}로 이유를 알린다. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, String>> unreadable() {
        return error(HttpStatus.BAD_REQUEST, "request body must be JSON, e.g. {\"enabled\": true}");
    }

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<Map<String, String>> notFound(NotFoundException e) {
        return error(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(RawSiteFormException.class)
    ResponseEntity<Map<String, String>> rawSite() {
        return error(HttpStatus.CONFLICT, "raw sites cannot be changed through the API");
    }

    @ExceptionHandler({ApplyInProgressException.class, ConcurrentEditException.class})
    ResponseEntity<Map<String, String>> busy() {
        return error(HttpStatus.CONFLICT, "another change or apply is in progress, try again");
    }

    @ExceptionHandler(NginxCommandException.class)
    ResponseEntity<Map<String, String>> agentFailed(NginxCommandException e) {
        return error(HttpStatus.BAD_GATEWAY, e.getMessage());
    }
}
