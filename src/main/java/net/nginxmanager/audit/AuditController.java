package net.nginxmanager.audit;

import net.nginxmanager.common.Messages;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Controller
public class AuditController {

    static final int PAGE_SIZE = 50;

    private final AuditQueryRepository auditQueryRepository;
    private final Messages messages;
    private final Clock clock;

    public AuditController(AuditQueryRepository auditQueryRepository, Messages messages, Clock clock) {
        this.auditQueryRepository = auditQueryRepository;
        this.messages = messages;
        this.clock = clock;
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

    /** 화면과 같은 조건의 감사 로그를 CSV로 내려준다(030). 최근 {@value AuditCsv#MAX_ROWS}줄까지. */
    @GetMapping("/audit/export")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) String username,
                                         @RequestParam(required = false) AuditAction action,
                                         @RequestParam(required = false) AuditResult result,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        ZoneId zone = ZoneId.systemDefault();
        AuditQueryRepository.Filter filter = new AuditQueryRepository.Filter(username, action, result, from, to);
        List<AuditLogQueryRepository.AuditLogRow> rows = auditQueryRepository.search(filter, zone, 0, AuditCsv.MAX_ROWS);
        List<String> header = List.of("time", "user", "role", "action", "target", "result", "detail").stream()
                .map(column -> messages.get("audit.list.col." + column)).toList();
        String csv = AuditCsv.write(header, rows, zone,
                value -> messages.get("enum." + value.getClass().getSimpleName() + "." + value.name()));
        String name = "audit-" + DateTimeFormatter.BASIC_ISO_DATE.format(LocalDate.now(clock.withZone(zone))) + ".csv";
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
                .body(csv.getBytes(StandardCharsets.UTF_8));
    }
}
