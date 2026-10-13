package net.nginxmanager.site;

import jakarta.validation.Valid;
import net.nginxmanager.common.NotFoundException;
import net.nginxmanager.cert.CertificateSource;
import net.nginxmanager.cert.TlsCertificate;
import net.nginxmanager.cert.TlsCertificateRepository;
import net.nginxmanager.config.ConfigFileStore;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Locale;

@Controller
@RequestMapping("/sites")
public class SiteController {

    private final SiteService siteService;
    private final SiteQueryRepository siteQueryRepository;
    private final SiteRendering rendering;
    private final ConfigFileStore store;
    private final TlsCertificateRepository certificateRepository;

    public SiteController(SiteService siteService, SiteQueryRepository siteQueryRepository,
                          SiteRendering rendering, ConfigFileStore store,
                          TlsCertificateRepository certificateRepository) {
        this.siteService = siteService;
        this.siteQueryRepository = siteQueryRepository;
        this.rendering = rendering;
        this.store = store;
        this.certificateRepository = certificateRepository;
    }

    /** 사이트 폼의 인증서 선택 목록(업로드한 인증서). */
    @ModelAttribute("uploadedCertificates")
    public java.util.List<TlsCertificate> uploadedCertificates() {
        return certificateRepository.findBySourceOrderByNameAsc(CertificateSource.UPLOADED);
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("sites", siteQueryRepository.findAllRows());
        return "sites/list";
    }

    @GetMapping("/new")
    public String newForm(Model model) {
        model.addAttribute("form", SiteForm.empty());
        return "sites/form";
    }

    @PostMapping("/new")
    public String create(@Valid @ModelAttribute("form") SiteForm form, BindingResult bindingResult,
                         Authentication authentication) {
        if (bindingResult.hasErrors()) {
            return "sites/form";
        }
        try {
            Site site = siteService.create(form, authentication.getName());
            return "redirect:/sites/" + site.getId() + "?saved";
        } catch (DuplicateSiteException e) {
            bindingResult.rejectValue("primaryDomain", "duplicate", "이미 등록된 도메인입니다.");
            return "sites/form";
        }
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable long id, Model model) {
        Site site = siteQueryRepository.findWithFile(id).orElseThrow(() -> new NotFoundException("site " + id));
        model.addAttribute("site", site);
        model.addAttribute("form", rendering.formOf(site));
        model.addAttribute("content", currentContent(site));
        model.addAttribute("certificate", certificateOf(site));
        return "sites/detail";
    }

    @PostMapping("/{id}")
    public String update(@PathVariable long id, @Valid @ModelAttribute("form") SiteForm form,
                         BindingResult bindingResult, Authentication authentication, Model model) {
        if (bindingResult.hasErrors()) {
            Site site = siteQueryRepository.findWithFile(id).orElseThrow(() -> new NotFoundException("site " + id));
            model.addAttribute("site", site);
            model.addAttribute("content", currentContent(site));
            model.addAttribute("certificate", certificateOf(site));
            return "sites/detail";
        }
        siteService.update(id, form, authentication.getName());
        return "redirect:/sites/" + id + "?saved";
    }

    @PostMapping("/{id}/enabled")
    public String setEnabled(@PathVariable long id, @RequestParam boolean enabled, @RequestParam long version,
                             Authentication authentication) {
        siteService.setEnabled(id, enabled, version, authentication.getName());
        return "redirect:/sites/" + id + "?saved";
    }

    /** 폼 값으로 만들 설정을 저장 전에 미리 본다(htmx). */
    @PostMapping("/preview")
    public String preview(@Valid @ModelAttribute("form") SiteForm form, BindingResult bindingResult, Model model) {
        model.addAttribute("preview", bindingResult.hasErrors()
                ? null
                : rendering.render(form.primaryDomain().toLowerCase(Locale.ROOT), form, form.certificateId()));
        return "sites/form :: preview";
    }

    private TlsCertificate certificateOf(Site site) {
        return site.getCertificateId() == null ? null
                : certificateRepository.findById(site.getCertificateId()).orElse(null);
    }

    private String currentContent(Site site) {
        String draft = site.getConfigFile().getDraftContent();
        return draft != null ? draft : store.read(site.getConfigFile().getPath());
    }
}
