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

    @GetMapping("/{id}")
    public String edit(@PathVariable long id, Model model) {
        ConfigFile file = configFileRepository.findById(id).orElseThrow(() -> new NotFoundException("file " + id));
        model.addAttribute("file", file);
        model.addAttribute("content", file.getDraftContent() != null ? file.getDraftContent() : store.read(file.getPath()));
        model.addAttribute("site", siteRepository.findByConfigFileId(id).orElse(null));
        return "files/edit";
    }

    @PostMapping("/{id}")
    public String save(@PathVariable long id, @RequestParam String content, @RequestParam long version,
                       Authentication authentication) {
        siteService.editRaw(id, content, version, authentication.getName());
        return "redirect:/files/" + id + "?saved";
    }
}
