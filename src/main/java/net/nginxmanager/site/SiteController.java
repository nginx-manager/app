package net.nginxmanager.site;

import jakarta.validation.Valid;
import net.nginxmanager.common.Messages;
import net.nginxmanager.common.NotFoundException;
import net.nginxmanager.cert.CertificateService;
import net.nginxmanager.cert.CertificateSource;
import net.nginxmanager.cert.TlsCertificate;
import net.nginxmanager.cert.TlsCertificateRepository;
import net.nginxmanager.config.ConfigFileStore;
import net.nginxmanager.user.UserRepository;
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
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

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
    private final ImportFiles importFiles;
    private final SiteAccess access;
    private final UserRepository userRepository;
    private final Messages messages;

    public SiteController(SiteService siteService, SiteQueryRepository siteQueryRepository,
                          SiteRendering rendering, ConfigFileStore store,
                          TlsCertificateRepository certificateRepository, CertificateService certificateService,
                          ImportFiles importFiles, SiteAccess access, UserRepository userRepository, Messages messages) {
        this.messages = messages;
        this.access = access;
        this.userRepository = userRepository;
        this.importFiles = importFiles;
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

    /**
     * 사이트 목록. 멤버는 담당 사이트만, 관리자는 전체를 담당자로 걸러 본다.
     *
     * @param owner 관리자의 담당자 거르기: 비우면 전체, none이면 담당자 없음, 숫자면 그 사용자
     */
    @GetMapping
    public String list(@RequestParam(required = false) String owner, Authentication authentication, Model model) {
        SiteQueryRepository.OwnerFilter filter = access.memberId(authentication)
                .<SiteQueryRepository.OwnerFilter>map(SiteQueryRepository.OwnerFilter.Owner::new)
                .orElseGet(() -> adminFilter(owner));
        model.addAttribute("sites", siteQueryRepository.findRows(filter));
        if (SiteAccess.isAdmin(authentication)) {
            model.addAttribute("users", userRepository.findAllByOrderByUsernameAsc());
            model.addAttribute("ownerFilter", owner == null ? "" : owner);
        }
        return "sites/list";
    }

    private static SiteQueryRepository.OwnerFilter adminFilter(String owner) {
        if (owner == null || owner.isBlank()) {
            return SiteQueryRepository.OwnerFilter.ALL;
        }
        if (owner.equals("none")) {
            return SiteQueryRepository.OwnerFilter.NONE;
        }
        try {
            return new SiteQueryRepository.OwnerFilter.Owner(Long.parseLong(owner));
        } catch (NumberFormatException e) {
            return SiteQueryRepository.OwnerFilter.ALL;
        }
    }

    /** @param copy 이 사이트 값으로 채운다(024, 사이트 복제). 원문 편집 사이트면 빈 폼 */
    @GetMapping("/new")
    public String newForm(@RequestParam(required = false) Long copy, Authentication authentication, Model model) {
        SiteForm form = SiteForm.empty();
        if (copy != null) {
            Site site = siteQueryRepository.findWithFile(copy).orElseThrow(() -> new NotFoundException("site " + copy));
            access.check(site, authentication);
            if (site.getType() != SiteType.RAW) {
                form = rendering.formOf(site).copyForNewSite();
                model.addAttribute("copiedFrom", site.getPrimaryDomain());
            }
        }
        model.addAttribute("form", form);
        return "sites/form";
    }

    @PostMapping("/new")
    public String create(@Valid @ModelAttribute("form") SiteForm form, BindingResult bindingResult,
                         Authentication authentication) {
        if (bindingResult.hasErrors()) {
            return "sites/form";
        }
        try {
            // 멤버가 만든 사이트는 그 멤버가 담당한다. 관리자가 만든 사이트는 담당자 없이 시작한다
            Site site = siteService.create(form, authentication.getName(), access.ownerOfNewSite(authentication));
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
    public String detail(@PathVariable long id, Authentication authentication, Model model) {
        Site site = siteQueryRepository.findWithFile(id).orElseThrow(() -> new NotFoundException("site " + id));
        access.check(site, authentication);
        model.addAttribute("form", rendering.formOf(site));
        addDetail(site, authentication, model);
        return "sites/detail";
    }

    @PostMapping("/{id}")
    public String update(@PathVariable long id, @Valid @ModelAttribute("form") SiteForm form,
                         BindingResult bindingResult, Authentication authentication, Model model) {
        access.require(id, authentication);
        if (bindingResult.hasErrors()) {
            Site site = siteQueryRepository.findWithFile(id).orElseThrow(() -> new NotFoundException("site " + id));
            addDetail(site, authentication, model);
            return "sites/detail";
        }
        try {
            siteService.update(id, form, authentication.getName());
        } catch (DuplicateSiteException e) {
            bindingResult.rejectValue("extraDomains", "msg.site.domainTaken", new Object[] {e.getDomain()}, duplicateMessage(e));
            Site site = siteQueryRepository.findWithFile(id).orElseThrow(() -> new NotFoundException("site " + id));
            addDetail(site, authentication, model);
            return "sites/detail";
        } catch (PortInUseException e) {
            rejectPort(bindingResult, e);
            Site site = siteQueryRepository.findWithFile(id).orElseThrow(() -> new NotFoundException("site " + id));
            addDetail(site, authentication, model);
            return "sites/detail";
        } catch (RawSiteFormException e) {
            // 폼을 연 사이 원문 편집기로 고쳐졌다. 폼 값으로 덮어쓰지 않는다
            return "redirect:/sites/" + id + "?rawOnly";
        } catch (MissingPasswordException e) {
            rejectMissingPassword(bindingResult, e);
            Site site = siteQueryRepository.findWithFile(id).orElseThrow(() -> new NotFoundException("site " + id));
            addDetail(site, authentication, model);
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
        access.require(id, authentication);
        siteService.setEnabled(id, enabled, version, authentication.getName());
        return "redirect:/sites/" + id + "?saved";
    }

    /** 사이트를 지우는 초안을 만든다(013). 한 번도 적용하지 않은 사이트는 바로 없어진다. 확인은 화면에서 받는다. */
    @PostMapping("/{id}/delete")
    public String delete(@PathVariable long id, @RequestParam long version, Authentication authentication,
                         RedirectAttributes redirect) {
        Site site = access.require(id, authentication);
        try {
            if (siteService.delete(id, version, authentication.getName())) {
                redirect.addFlashAttribute("notice", messages.get("msg.site.deleted", site.getPrimaryDomain()));
                return "redirect:/sites";
            }
        } catch (SitePendingException e) {
            redirect.addFlashAttribute("error", messages.get("msg.site.deletePending"));
            return "redirect:/sites/" + id;
        }
        return "redirect:/sites/" + id + "?deleting";
    }

    /** 관리자가 담당자를 지정·변경·해제한다(SecurityConfig에서 관리자 전용). 설정 파일은 바뀌지 않는다. */
    @PostMapping("/{id}/owner")
    public String assignOwner(@PathVariable long id, @RequestParam(required = false) Long owner,
                              @RequestParam long version, Authentication authentication) {
        siteService.assignOwner(id, owner, version, authentication.getName());
        return "redirect:/sites/" + id + "?assigned";
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
    private void addDetail(Site site, Authentication authentication, Model model) {
        if (SiteAccess.isAdmin(authentication)) {
            model.addAttribute("users", userRepository.findAllByOrderByUsernameAsc());
        }
        TlsCertificate certificate = certificateOf(site);
        model.addAttribute("site", site);
        model.addAttribute("content", currentContent(site));
        model.addAttribute("certificate", certificate);
        model.addAttribute("certificateRunning", certificate != null && certificateService.isRunning(certificate.getId()));
        model.addAttribute("siteLive", site.isEnabled() && store.read(site.getConfigFile().getPath()) != null);
        model.addAttribute("importChanged", importChanged(site));
        // 원문 사이트: 원문(초안 포함)에 HTTPS server 블록이 있는지. 없으면 발급된 인증서로 더하는 버튼을 보인다
        String content = currentContent(site);
        model.addAttribute("rawHasHttps", site.getType() == SiteType.RAW && content != null && RawHttps.hasHttps(content));
    }

    /** 가져온 원본이 가져온 뒤 바뀌었는지. 원본을 볼 수 없으면(지웠거나 마운트가 빠짐) false. */
    private boolean importChanged(Site site) {
        if (site.getImportPath() == null || site.getImportHash() == null) {
            return false;
        }
        try {
            return importFiles.resolve(site.getImportPath())
                    .map(importFiles::read)
                    .map(content -> !ConfigFileStore.hash(content).equals(site.getImportHash()))
                    .orElse(false);
        } catch (RuntimeException e) {
            return false;
        }
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
