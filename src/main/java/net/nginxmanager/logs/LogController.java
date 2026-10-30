package net.nginxmanager.logs;

import net.nginxmanager.site.Site;
import net.nginxmanager.site.SiteAccess;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

/** 사이트별 access/error 로그 검색과 실시간 보기(FR-015). */
@Controller
public class LogController {

    static final int SEARCH_LIMIT = 500;
    /** 검색 한 번에 파일 끝에서 읽는 최대 양. */
    static final long SEARCH_MAX_BYTES = 64L * 1024 * 1024;

    private final SiteAccess access;
    private final LogFiles logFiles;
    private final LogTailService tailService;

    public LogController(SiteAccess access, LogFiles logFiles, LogTailService tailService) {
        this.access = access;
        this.logFiles = logFiles;
        this.tailService = tailService;
    }

    @GetMapping("/logs/{siteId}")
    public String search(@PathVariable long siteId, @RequestParam(defaultValue = "ACCESS") LogType type,
                         @RequestParam(required = false) String q, Authentication authentication,
                         Model model) throws IOException {
        Site site = access.require(siteId, authentication);
        String query = q == null ? null : q.strip();
        model.addAttribute("site", site);
        model.addAttribute("type", type);
        model.addAttribute("q", query);
        model.addAttribute("result", LogSearch.search(logFiles.of(site, type), query, SEARCH_LIMIT, SEARCH_MAX_BYTES));
        model.addAttribute("limit", SEARCH_LIMIT);
        return "logs/view";
    }

    @GetMapping(path = "/logs/{siteId}/stream", produces = "text/event-stream")
    @ResponseBody
    public SseEmitter stream(@PathVariable long siteId, @RequestParam(defaultValue = "ACCESS") LogType type,
                             Authentication authentication) throws IOException {
        return tailService.open(logFiles.of(access.require(siteId, authentication), type));
    }
}
