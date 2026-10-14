package net.nginxmanager.config;

import net.nginxmanager.common.NotFoundException;
import net.nginxmanager.site.SiteRepository;
import net.nginxmanager.site.SiteService;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequestMapping("/files")
public class FileEditorController {

    private final ConfigFileRepository configFileRepository;
    private final SiteRepository siteRepository;
    private final SiteService siteService;
    private final ConfigFileStore store;

    public FileEditorController(ConfigFileRepository configFileRepository, SiteRepository siteRepository,
                                SiteService siteService, ConfigFileStore store) {
        this.configFileRepository = configFileRepository;
        this.siteRepository = siteRepository;
        this.siteService = siteService;
        this.store = store;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("files", configFileRepository.findAllByOrderByPathAsc());
        return "files/list";
    }

    @GetMapping("/new")
    public String newFile(Model model) {
        model.addAttribute("path", "");
        model.addAttribute("content", "");
        return "files/new";
    }

    /** 새 파일 초안. 폼이 없는 모듈(njs, mail, stream include 등)의 설정을 둘 곳을 만든다. */
    @PostMapping("/new")
    public String create(@RequestParam String path, @RequestParam(defaultValue = "") String content,
                         Authentication authentication, Model model) {
        String trimmed = path.strip();
        String error = null;
        if (!NewFilePath.isValid(trimmed)) {
            error = "files.new.error.path";
        } else {
            try {
                ConfigFile file = siteService.createFile(trimmed, content, authentication.getName());
                return "redirect:/files/" + file.getId() + "?saved";
            } catch (IllegalArgumentException e) {
                error = "files.new.error.exists";
            }
        }
        model.addAttribute("path", trimmed);
        model.addAttribute("content", content);
        model.addAttribute("errorKey", error);
        return "files/new";
    }

    @GetMapping("/{id}")
    public String edit(@PathVariable long id, Model model) {
        ConfigFile file = configFileRepository.findById(id).orElseThrow(() -> new NotFoundException("file " + id));
        model.addAttribute("file", file);
        model.addAttribute("content", file.getDraftContent() != null ? file.getDraftContent() : store.read(file.getPath()));
        model.addAttribute("site", siteRepository.findByConfigFileId(id).orElse(null));
        model.addAttribute("deletable", siteService.isDeletable(file) && !file.isDraftDelete());
        return "files/edit";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable long id, @RequestParam long version, Authentication authentication) {
        ConfigFile file = siteService.deleteFile(id, version, authentication.getName());
        return file.isDraftDelete() ? "redirect:/files/" + id + "?deleted" : "redirect:/files?deleted";
    }

    @PostMapping("/{id}")
    public String save(@PathVariable long id, @RequestParam String content, @RequestParam long version,
                       Authentication authentication) {
        siteService.editRaw(id, content, version, authentication.getName());
        return "redirect:/files/" + id + "?saved";
    }
}
