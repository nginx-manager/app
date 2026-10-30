package net.nginxmanager.logs;

import net.nginxmanager.common.NotFoundException;
import net.nginxmanager.site.Site;
import net.nginxmanager.site.SiteAccess;
import net.nginxmanager.site.SiteService;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;

/** 사이트별 access/error 로그 검색과 실시간 보기(FR-015), 원문 사이트 로그와 지난 로그(011). */
@Controller
public class LogController {

    static final int SEARCH_LIMIT = 500;
    /** 검색 한 번에 파일 끝에서 읽는 최대 양. */
    static final long SEARCH_MAX_BYTES = 64L * 1024 * 1024;

    private final SiteAccess access;
    private final LogFiles logFiles;
    private final LogTailService tailService;
    private final SiteService siteService;

    public LogController(SiteAccess access, LogFiles logFiles, LogTailService tailService, SiteService siteService) {
        this.access = access;
        this.logFiles = logFiles;
        this.tailService = tailService;
        this.siteService = siteService;
    }

    @GetMapping("/logs/{siteId}")
    public String search(@PathVariable long siteId, @RequestParam(defaultValue = "ACCESS") LogType type,
                         @RequestParam(required = false) String q, @RequestParam(defaultValue = "0") String file,
                         Authentication authentication, Model model) throws IOException {
        Site site = access.require(siteId, authentication);
        String query = q == null ? null : q.strip();
        LogLocation location = logFiles.locate(site, type);
        List<LogFiles.LogFile> files = logFiles.history(location);
        // 번호로만 고른다. 없는 번호나 숫자가 아니면 지금 파일
        LogFiles.LogFile selected = files.stream().filter(f -> String.valueOf(f.index()).equals(file)).findFirst()
                .orElse(files.isEmpty() ? null : files.getFirst());
        model.addAttribute("site", site);
        model.addAttribute("type", type);
        model.addAttribute("q", query);
        model.addAttribute("location", location);
        model.addAttribute("files", files);
        model.addAttribute("selected", selected);
        model.addAttribute("result", selected == null ? null
                : LogSearch.search(selected.path(), query, SEARCH_LIMIT, SEARCH_MAX_BYTES));
        model.addAttribute("limit", SEARCH_LIMIT);
        return "logs/view";
    }

    @GetMapping(path = "/logs/{siteId}/stream", produces = "text/event-stream")
    @ResponseBody
    public SseEmitter stream(@PathVariable long siteId, @RequestParam(defaultValue = "ACCESS") LogType type,
                             Authentication authentication) throws IOException {
        LogLocation location = logFiles.locate(access.require(siteId, authentication), type);
        if (!location.readable()) {
            throw new NotFoundException("log of site " + siteId);
        }
        return tailService.open(location.file());
    }

    /** 원문 사이트에 사이트별 로그를 더한 초안을 만든다(관리자만, SecurityConfig). */
    @PostMapping("/logs/{siteId}/per-site")
    public String addSiteLogs(@PathVariable long siteId, @RequestParam(defaultValue = "ACCESS") LogType type,
                              Authentication authentication) {
        access.require(siteId, authentication);
        boolean staged = siteService.addRawSiteLogs(siteId, authentication.getName());
        return "redirect:/logs/" + siteId + "?type=" + type + (staged ? "&staged" : "");
    }
}
