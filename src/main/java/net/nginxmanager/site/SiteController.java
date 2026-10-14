package net.nginxmanager.site;

import jakarta.validation.Valid;
import net.nginxmanager.common.NotFoundException;
import net.nginxmanager.cert.CertificateService;
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
    private final CertificateService certificateService;

    public SiteController(SiteService siteService, SiteQueryRepository siteQueryRepository,
                          SiteRendering rendering, ConfigFileStore store,
                          TlsCertificateRepository certificateRepository, CertificateService certificateService) {
        this.certificateService = certificateService;
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

    /**
     * HTTP/3를 켤 수 있는지: nginx.conf에 443/UDP를 reuseport로 여는 기본 서버가 있어야 한다
     * (예전 기본 설정으로 시작한 설치에는 없다).
     */
    @ModelAttribute("http3Ready")
    public boolean http3Ready() {
        String conf = store.read("nginx.conf");
        return conf != null && conf.lines().anyMatch(line -> {
            String trimmed = line.strip();
            return !trimmed.startsWith("#") && trimmed.startsWith("listen") && trimmed.contains("quic")
                    && trimmed.contains("reuseport");
        });
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
            bindingResult.rejectValue("primaryDomain", "msg.site.domainTaken", new Object[] {e.getDomain()}, duplicateMessage(e));
            return "sites/form";
        } catch (PortInUseException e) {
            rejectPort(bindingResult, e);
            return "sites/form";
        } catch (MissingPasswordException e) {
            rejectMissingPassword(bindingResult, e);
            return "sites/form";
        }
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable long id, Model model) {
        Site site = siteQueryRepository.findWithFile(id).orElseThrow(() -> new NotFoundException("site " + id));
        model.addAttribute("form", rendering.formOf(site));
        addDetail(site, model);
        return "sites/detail";
    }

    @PostMapping("/{id}")
    public String update(@PathVariable long id, @Valid @ModelAttribute("form") SiteForm form,
                         BindingResult bindingResult, Authentication authentication, Model model) {
        if (bindingResult.hasErrors()) {
            Site site = siteQueryRepository.findWithFile(id).orElseThrow(() -> new NotFoundException("site " + id));
            addDetail(site, model);
            return "sites/detail";
        }
        try {
            siteService.update(id, form, authentication.getName());
        } catch (DuplicateSiteException e) {
            bindingResult.rejectValue("extraDomains", "msg.site.domainTaken", new Object[] {e.getDomain()}, duplicateMessage(e));
            Site site = siteQueryRepository.findWithFile(id).orElseThrow(() -> new NotFoundException("site " + id));
            addDetail(site, model);
            return "sites/detail";
        } catch (PortInUseException e) {
            rejectPort(bindingResult, e);
            Site site = siteQueryRepository.findWithFile(id).orElseThrow(() -> new NotFoundException("site " + id));
            addDetail(site, model);
            return "sites/detail";
        } catch (RawSiteFormException e) {
            // 폼을 연 사이 원문 편집기로 고쳐졌다. 폼 값으로 덮어쓰지 않는다
            return "redirect:/sites/" + id + "?rawOnly";
        } catch (MissingPasswordException e) {
            rejectMissingPassword(bindingResult, e);
            Site site = siteQueryRepository.findWithFile(id).orElseThrow(() -> new NotFoundException("site " + id));
            addDetail(site, model);
            return "sites/detail";
        }
        return "redirect:/sites/" + id + "?saved";
    }

    private static void rejectMissingPassword(BindingResult bindingResult, MissingPasswordException e) {
        bindingResult.rejectValue("options.authUsers", "msg.site.authPasswordRequired",
                new Object[] {e.getUsername()}, "새 사용자에는 비밀번호가 필요합니다: " + e.getUsername());
    }

    private static void rejectPort(BindingResult bindingResult, PortInUseException e) {
        bindingResult.rejectValue("listenHttpPort", "msg.stream.portTaken",
                new Object[] {String.valueOf(e.getPort()), e.getUsedBy()}, "포트가 이미 쓰입니다: " + e.getPort());
    }

    private static String duplicateMessage(DuplicateSiteException e) {
        return "이미 다른 사이트가 쓰는 도메인입니다: " + e.getDomain();
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

    /**
     * 상세 화면의 사이트·설정 원문·인증서 상태. siteLive는 nginx가 지금 이 사이트를 서비스하는지(적용했고 켜져 있는지)로,
     * 서비스 중이어야 Let's Encrypt가 도메인을 확인할 수 있어 바로 발급할 수 있다.
     */
    private void addDetail(Site site, Model model) {
        TlsCertificate certificate = certificateOf(site);
        model.addAttribute("site", site);
        model.addAttribute("content", currentContent(site));
        model.addAttribute("certificate", certificate);
        model.addAttribute("certificateRunning", certificate != null && certificateService.isRunning(certificate.getId()));
        model.addAttribute("siteLive", site.isEnabled() && store.read(site.getConfigFile().getPath()) != null);
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
