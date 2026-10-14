package net.nginxmanager.audit;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

@Controller
public class AuditController {

    static final int PAGE_SIZE = 50;

    private final AuditQueryRepository auditQueryRepository;

    public AuditController(AuditQueryRepository auditQueryRepository) {
        this.auditQueryRepository = auditQueryRepository;
    }

    @GetMapping("/audit")
    public String list(@RequestParam(required = false) String username,
                       @RequestParam(required = false) AuditAction action,
                       @RequestParam(required = false) AuditResult result,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                       @RequestParam(defaultValue = "0") int page,
                       Model model) {
        int safePage = Math.max(page, 0);
        AuditQueryRepository.Filter filter = new AuditQueryRepository.Filter(username, action, result, from, to);
        List<AuditLogQueryRepository.AuditLogRow> rows =
                auditQueryRepository.search(filter, ZoneId.systemDefault(), safePage * PAGE_SIZE, PAGE_SIZE + 1);
        model.addAttribute("filter", filter);
        model.addAttribute("logs", rows.stream().limit(PAGE_SIZE).toList());
        model.addAttribute("page", safePage);
        model.addAttribute("hasNext", rows.size() > PAGE_SIZE);
        model.addAttribute("actions", AuditAction.values());
        model.addAttribute("results", AuditResult.values());
        return "audit/list";
    }
}
