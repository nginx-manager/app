package net.nginxmanager.site;

import net.nginxmanager.audit.AuditAction;
import net.nginxmanager.audit.AuditEvent;
import net.nginxmanager.common.NotFoundException;
import net.nginxmanager.config.ConfigFile;
import net.nginxmanager.config.ConfigFileRepository;
import net.nginxmanager.config.ConfigFileStore;
import net.nginxmanager.config.UnifiedDiff;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 설정 파일을 사이트로 가져오기(spec 004). 경로 패턴으로 찾은 파일이나 올린 파일을 읽어 미리 보고, 폼 또는 원문 사이트 초안으로
 * 등록한다. 등록해 둔 패턴은 다시 검사해 새 파일과 원본이 바뀐 사이트를 보여 준다.
 */
@Controller
@RequestMapping("/sites")
public class SiteImportController {

    private final ImportFiles files;
    private final SiteImporter importer;
    private final SiteService siteService;
    private final SiteRepository siteRepository;
    private final ImportSourceRepository sourceRepository;
    private final ConfigFileRepository configFileRepository;
    private final SiteRendering rendering;
    private final ApplicationEventPublisher events;

    public SiteImportController(ImportFiles files, SiteImporter importer, SiteService siteService,
                                SiteRepository siteRepository, ImportSourceRepository sourceRepository,
                                ConfigFileRepository configFileRepository,
                                SiteRendering rendering, ApplicationEventPublisher events) {
        this.files = files;
        this.importer = importer;
        this.siteService = siteService;
        this.siteRepository = siteRepository;
        this.sourceRepository = sourceRepository;
        this.configFileRepository = configFileRepository;
        this.rendering = rendering;
        this.events = events;
    }

    /** 검사 결과의 한 줄. */
    public enum RowStatus {
        /** 새로 등록할 수 있다. */
        NEW,
        /** 이미 이 파일에서 가져온 사이트가 있고 원본이 그대로다. */
        REGISTERED,
        /** 이 파일에서 가져온 사이트가 있는데 원본이 바뀌었다. */
        CHANGED,
        /** 대표 도메인이 다른 사이트와 겹친다. */
        TAKEN,
        /** 사이트로 등록할 수 없다. */
        INVALID
    }

    /**
     * @param site 이 파일에서 가져온 사이트, 또는 도메인이 겹치는 사이트
     */
    public record Row(ImportFiles.Found file, RowStatus status, SiteImportAnalysis analysis, Site site,
                      String errorKey, String errorArg) {

        public boolean selectable() {
            return status == RowStatus.NEW && analysis.primaryDomain() != null;
        }
    }

    @GetMapping("/import")
    public String page(@RequestParam(required = false) String pattern, @RequestParam(required = false) String scan,
                       Model model) {
        List<ImportSource> sources = sourceRepository.findAllByOrderByPatternAsc();
        model.addAttribute("sources", sources);
        model.addAttribute("roots", files.roots());
        model.addAttribute("pattern", pattern);
        List<ImportFiles.Scan> scans = new ArrayList<>();
        if (pattern != null && !pattern.isBlank()) {
            scans.add(files.scan(pattern));
        } else if (scan != null) {
            sources.forEach(source -> scans.add(files.scan(source.getPattern())));
        }
        model.addAttribute("scans", scans.stream().map(s -> new ScanView(s, rows(s))).toList());
        model.addAttribute("scanned", !scans.isEmpty());
        return "sites/import";
    }

    public record ScanView(ImportFiles.Scan scan, List<Row> rows) {
    }

    private List<Row> rows(ImportFiles.Scan scan) {
        List<Row> rows = new ArrayList<>();
        for (ImportFiles.Found file : scan.files()) {
            rows.add(row(file));
        }
        return rows;
    }

    private Row row(ImportFiles.Found file) {
        String content;
        try {
            content = files.read(file);
        } catch (IllegalArgumentException e) {
            return new Row(file, RowStatus.INVALID, null, null, e.getMessage(), null);
        }
        Optional<Site> imported = siteRepository.findByImportPath(file.path()).stream().findFirst();
        SiteImportAnalysis analysis = importer.analyze(content, imported.map(Site::getPrimaryDomain).orElse(null));
        if (imported.isPresent()) {
            boolean same = ConfigFileStore.hash(content).equals(imported.get().getImportHash());
            return new Row(file, same ? RowStatus.REGISTERED : RowStatus.CHANGED, analysis, imported.get(), null, null);
        }
        if (!analysis.importable()) {
            return new Row(file, RowStatus.INVALID, analysis, null, analysis.errorKey(), analysis.errorArg());
        }
        if (analysis.primaryDomain() != null) {
            Optional<Site> taken = siteRepository.findByPrimaryDomain(analysis.primaryDomain());
            if (taken.isPresent()) {
                return new Row(file, RowStatus.TAKEN, analysis, taken.get(), null, null);
            }
        }
        return new Row(file, RowStatus.NEW, analysis, null, null, null);
    }

    @PostMapping("/import/sources")
    public String addSource(@RequestParam String pattern, Authentication authentication,
                            RedirectAttributes redirect) {
        String trimmed = pattern == null ? "" : pattern.strip();
        ImportFiles.Scan scan = files.scan(trimmed);
        if (scan.errorKey() != null) {
            redirect.addFlashAttribute("errorKey", scan.errorKey());
            redirect.addFlashAttribute("errorArg", scan.errorArg());
            return "redirect:/sites/import";
        }
        if (!sourceRepository.existsByPattern(trimmed)) {
            sourceRepository.save(new ImportSource(trimmed, authentication.getName()));
            events.publishEvent(AuditEvent.success(authentication.getName(), AuditAction.SITE_IMPORT,
                    "source:" + trimmed + " add"));
        }
        return "redirect:/sites/import?scan";
    }

    @PostMapping("/import/sources/{id}/delete")
    public String deleteSource(@PathVariable long id, Authentication authentication) {
        sourceRepository.findById(id).ifPresent(source -> {
            sourceRepository.delete(source);
            events.publishEvent(AuditEvent.success(authentication.getName(), AuditAction.SITE_IMPORT,
                    "source:" + source.getPattern() + " remove"));
        });
        return "redirect:/sites/import";
    }

    /** 찾은 파일 하나를 미리 본다. */
    @GetMapping("/import/file")
    public String previewFile(@RequestParam String path, @RequestParam(required = false) String primaryDomain,
                              Model model) {
        ImportFiles.Found file = files.resolve(path).filter(f -> !ImportFiles.isReserved(f))
                .orElseThrow(() -> new NotFoundException("import file " + path));
        String content;
        try {
            content = files.read(file);
        } catch (IllegalArgumentException e) {
            model.addAttribute("errorKey", e.getMessage());
            return page(null, null, model);
        }
        return preview(file.path(), file.relativePath(), content, primaryDomain, false, model);
    }

    /** 올린 파일을 미리 본다. 내용은 등록할 때까지 화면의 숨은 값으로 들고 간다. */
    @PostMapping("/import/upload")
    public String previewUpload(@RequestParam("file") MultipartFile upload, Model model) throws IOException {
        String errorKey = null;
        String content = null;
        if (upload.isEmpty()) {
            errorKey = "import.error.emptyUpload";
        } else if (upload.getSize() > ImportFiles.MAX_BYTES) {
            errorKey = "import.error.tooLarge";
        } else {
            try {
                content = ImportFiles.decode(upload.getBytes());
            } catch (IllegalArgumentException e) {
                errorKey = e.getMessage();
            }
        }
        if (errorKey != null) {
            model.addAttribute("errorKey", errorKey);
            return page(null, null, model);
        }
        String name = upload.getOriginalFilename() == null ? "upload.conf" : upload.getOriginalFilename();
        return preview(name, null, content, null, true, model);
    }

    private String preview(String sourcePath, String rootRelative, String content, String primaryDomain,
                           boolean uploaded, Model model) {
        SiteImportAnalysis analysis = importer.analyze(content, primaryDomain);
        model.addAttribute("sourcePath", sourcePath);
        model.addAttribute("rootRelative", rootRelative);
        model.addAttribute("uploaded", uploaded);
        model.addAttribute("content", content);
        model.addAttribute("contentHash", ConfigFileStore.hash(content));
        model.addAttribute("analysis", analysis);
        model.addAttribute("primaryDomain", analysis.primaryDomain());
        model.addAttribute("existing", siteRepository.findByImportPath(sourcePath).stream().findFirst()
                .filter(s -> !uploaded).orElse(null));
        model.addAttribute("sourcePending", rootRelative != null && configFileRepository.findByPath(rootRelative)
                .map(ConfigFile::hasPendingChange).orElse(false));
        if (analysis.formPossible()) {
            String rendered = rendering.render(analysis.primaryDomain(), analysis.form(), null);
            model.addAttribute("rendered", rendered);
            model.addAttribute("renderedDiff", UnifiedDiff.of(sourcePath, content, rendered));
        }
        return "sites/import-preview";
    }

    /** 미리 본 파일 하나를 등록한다. */
    @PostMapping("/import")
    public String importOne(@RequestParam(required = false) String path, @RequestParam(required = false) String upload,
                            @RequestParam(required = false) String uploadName, @RequestParam String contentHash,
                            @RequestParam(required = false) String primaryDomain,
                            @RequestParam(defaultValue = "RAW") String mode,
                            @RequestParam(defaultValue = "false") boolean removeSource,
                            Authentication authentication, Model model) {
        String sourcePath;
        String rootRelative;
        String content;
        if (path != null && !path.isBlank()) {
            ImportFiles.Found file = files.resolve(path).filter(f -> !ImportFiles.isReserved(f))
                    .orElseThrow(() -> new NotFoundException("import file " + path));
            content = files.read(file);
            sourcePath = file.path();
            rootRelative = file.relativePath();
        } else {
            content = upload == null ? "" : upload;
            sourcePath = uploadName == null || uploadName.isBlank() ? "upload.conf" : uploadName;
            rootRelative = null;
        }
        if (!ConfigFileStore.hash(content).equals(contentHash)) {
            // 미리 본 뒤 원본이 바뀌었다: 바뀐 내용으로 다시 보여 준다
            model.addAttribute("errorKey", "import.error.changedSincePreview");
            return preview(sourcePath, rootRelative, content, primaryDomain, rootRelative == null && path == null, model);
        }
        try {
            Site site = siteService.importSite(new SiteService.ImportCommand(sourcePath, rootRelative, content,
                    primaryDomain, "FORM".equals(mode), removeSource), authentication.getName());
            return "redirect:/sites/" + site.getId() + "?imported";
        } catch (SiteImportException e) {
            model.addAttribute("errorKey", e.getKey());
            model.addAttribute("errorArg", e.getArg());
        } catch (DuplicateSiteException e) {
            model.addAttribute("errorKey", "msg.site.domainTaken");
            model.addAttribute("errorArg", e.getDomain());
        } catch (PortInUseException e) {
            model.addAttribute("errorKey", "import.error.portTaken");
            model.addAttribute("errorArg", e.getPort() + " (" + e.getUsedBy() + ")");
        }
        return preview(sourcePath, rootRelative, content, primaryDomain, path == null || path.isBlank(), model);
    }

    /**
     * 검사 결과에서 고른 파일을 한꺼번에 등록한다. 폼으로 옮길 수 있으면 폼 사이트로, 아니면 원문 사이트로 등록하고
     * 대표 도메인은 server_name의 첫 이름을 쓴다. 등록하지 못한 파일은 이유와 함께 알린다.
     */
    @PostMapping("/import/bulk")
    public String importBulk(@RequestParam(name = "paths", required = false) List<String> paths,
                             @RequestParam(defaultValue = "false") boolean removeSource,
                             @RequestParam(defaultValue = "true") boolean preferForm,
                             Authentication authentication, RedirectAttributes redirect) {
        List<String> imported = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (String path : paths == null ? List.<String>of() : paths) {
            Optional<ImportFiles.Found> file = files.resolve(path).filter(f -> !ImportFiles.isReserved(f));
            if (file.isEmpty()) {
                failed.add(path);
                continue;
            }
            try {
                String content = files.read(file.get());
                boolean form = preferForm && importer.analyze(content, null).formPossible();
                Site site = siteService.importSite(new SiteService.ImportCommand(file.get().path(),
                        file.get().relativePath(), content, null, form, removeSource), authentication.getName());
                imported.add(site.getPrimaryDomain());
            } catch (RuntimeException e) {
                failed.add(path);
            }
        }
        redirect.addFlashAttribute("importedSites", imported);
        redirect.addFlashAttribute("failedFiles", failed);
        return "redirect:/sites/import?scan";
    }

    /** 원본이 바뀐 가져온 사이트를 다시 가져온다. */
    @PostMapping("/{id}/reimport")
    public String reimport(@PathVariable long id, @RequestParam long version, Authentication authentication,
                           RedirectAttributes redirect) {
        Site site = siteRepository.findById(id).orElseThrow(() -> new NotFoundException("site " + id));
        Optional<ImportFiles.Found> file = Optional.ofNullable(site.getImportPath()).flatMap(files::resolve);
        if (file.isEmpty()) {
            redirect.addFlashAttribute("errorKey", "import.error.sourceGone");
            redirect.addFlashAttribute("errorArg", site.getImportPath());
            return "redirect:/sites/" + id;
        }
        try {
            siteService.reimport(id, files.read(file.get()), version, authentication.getName());
        } catch (SiteImportException e) {
            redirect.addFlashAttribute("errorKey", e.getKey());
            redirect.addFlashAttribute("errorArg", e.getArg());
            return "redirect:/sites/" + id;
        } catch (DuplicateSiteException e) {
            redirect.addFlashAttribute("errorKey", "msg.site.domainTaken");
            redirect.addFlashAttribute("errorArg", e.getDomain());
            return "redirect:/sites/" + id;
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("errorKey", e.getMessage());
            return "redirect:/sites/" + id;
        }
        return "redirect:/sites/" + id + "?saved";
    }
}
