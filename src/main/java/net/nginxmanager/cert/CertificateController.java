package net.nginxmanager.cert;

import net.nginxmanager.common.Messages;
import net.nginxmanager.common.NotFoundException;
import net.nginxmanager.site.Site;
import net.nginxmanager.site.SiteRepository;
import net.nginxmanager.site.SiteAccess;
import net.nginxmanager.site.SiteService;
import net.nginxmanager.site.SiteType;
import net.nginxmanager.site.WildcardLetsEncryptException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Controller
public class CertificateController {

    static final String ISSUED_TAB = "issued";
    static final String PENDING_TAB = "pending";

    private final TlsCertificateRepository certificateRepository;
    private final SiteRepository siteRepository;
    private final CertificateService certificateService;
    private final CertificateUploadService uploadService;
    private final SiteService siteService;
    private final Clock clock;
    private final Messages messages;
    private final SiteAccess access;
    private final CertificateDeletion deletion;

    public CertificateController(TlsCertificateRepository certificateRepository, SiteRepository siteRepository,
                                 CertificateService certificateService, CertificateUploadService uploadService,
                                 SiteService siteService, Clock clock, Messages messages, SiteAccess access,
                                 CertificateDeletion deletion) {
        this.access = access;
        this.deletion = deletion;
        this.messages = messages;
        this.certificateRepository = certificateRepository;
        this.siteRepository = siteRepository;
        this.certificateService = certificateService;
        this.uploadService = uploadService;
        this.siteService = siteService;
        this.clock = clock;
    }

    /**
     * 인증서 목록. 발급된 인증서와 발급을 기다리거나 실패한 인증서를 탭으로 나눠 보인다.
     *
     * @param tab "pending"이면 발급 대기·실패 탭. 비우면 발급된 인증서가 있을 때 그 탭
     */
    @GetMapping("/certs")
    public String list(@RequestParam(required = false) String tab, Model model) {
        Map<Long, List<String>> sitesByCert = siteRepository.findByCertificateIdIsNotNull().stream()
                .collect(Collectors.groupingBy(Site::getCertificateId,
                        Collectors.mapping(Site::getPrimaryDomain, Collectors.toList())));
        List<TlsCertificate> all = certificateRepository.findAllByOrderByNameAsc();
        Map<Boolean, List<TlsCertificate>> byIssued = all.stream()
                .collect(Collectors.partitioningBy(c -> c.getStatus() == CertificateStatus.ACTIVE));
        List<TlsCertificate> issued = byIssued.get(true);
        List<TlsCertificate> pending = byIssued.get(false);
        boolean pendingTab = PENDING_TAB.equals(tab) || (tab == null && issued.isEmpty() && !pending.isEmpty());
        List<TlsCertificate> certificates = pendingTab ? pending : issued;
        model.addAttribute("tab", pendingTab ? PENDING_TAB : ISSUED_TAB);
        model.addAttribute("issuedCount", issued.size());
        model.addAttribute("pendingCount", pending.size());
        model.addAttribute("hasAny", !all.isEmpty());
        // 지울 수 없는 인증서와 그 이유(사이트는 한 번만 읽는다)
        List<Site> sites = siteRepository.findAll();
        Map<Long, String> deleteBlocked = new java.util.HashMap<>();
        for (TlsCertificate c : certificates) {
            deletion.blocker(c, sites).ifPresent(b -> deleteBlocked.put(c.getId(),
                    blockedMessage(b, CertificateDeletion.usersOf(c, sites))));
        }
        model.addAttribute("deleteBlocked", deleteBlocked);
        model.addAttribute("certificates", certificates);
        model.addAttribute("sitesByCert", sitesByCert);
        model.addAttribute("running", certificates.stream()
                .filter(c -> certificateService.isRunning(c.getId())).map(TlsCertificate::getId).toList());
        model.addAttribute("now", clock.instant());
        // 인증서 없이 만든 폼 사이트: 여기서도 Let's Encrypt 인증서를 받을 수 있다
        model.addAttribute("sitesWithoutHttps", siteRepository.findAll().stream()
                .filter(s -> !s.isHttpsEnabled())
                .sorted(Comparator.comparing(Site::getPrimaryDomain))
                .toList());
        return "certs/list";
    }

    /**
     * 지금 발급·갱신한다(실패한 발급 다시 시도 포함).
     *
     * @param siteId 사이트 화면에서 눌렀으면 그 사이트. 끝나면 사이트 화면으로 돌아간다
     */
    @PostMapping("/certs/{id}/renew")
    public String renew(@PathVariable long id, @RequestParam(required = false) Long siteId,
                        @RequestParam(required = false) String tab,
                        Authentication authentication, RedirectAttributes redirect) {
        if (!certificateRepository.existsById(id)) {
            throw new NotFoundException("certificate " + id);
        }
        if (!SiteAccess.isAdmin(authentication)) {
            // 멤버는 담당 사이트 화면에서, 그 사이트에 연결된 인증서만 갱신한다
            Site site = siteId == null ? null : access.require(siteId, authentication);
            if (site == null || !Long.valueOf(id).equals(site.getCertificateId())) {
                throw new NotFoundException("certificate " + id);
            }
        }
        certificateService.submit(id);
        redirect.addFlashAttribute("notice", messages.get("msg.cert.renewStarted"));
        if (siteId != null) {
            return "redirect:/sites/" + siteId + "#https";
        }
        return PENDING_TAB.equals(tab) ? "redirect:/certs?tab=" + PENDING_TAB : "redirect:/certs";
    }

    /**
     * 만들어 둔 폼 사이트에 Let's Encrypt HTTPS를 켠다. 서비스 중인 사이트는 바로 발급을 시작하고,
     * 아직 적용하지 않은(또는 꺼 둔) 사이트는 적용할 때 발급한다.
     *
     * @param from 인증서 화면에서 눌렀으면 "certs". 그 화면으로 돌아간다
     */
    @PostMapping("/certs/letsencrypt")
    public String letsEncrypt(@RequestParam long siteId, @RequestParam(required = false) String from,
                              Authentication authentication, RedirectAttributes redirect) {
        Site site = access.require(siteId, authentication);
        boolean raw = site.getType() == SiteType.RAW;
        if (raw && !SiteAccess.isAdmin(authentication)) {
            // 원문 사이트의 설정은 관리자만 고친다
            throw new NotFoundException("site " + siteId);
        }
        String back = "certs".equals(from) && SiteAccess.isAdmin(authentication) ? "redirect:/certs"
                : "redirect:/sites/" + siteId + "#https";
        SiteService.LetsEncryptRequest request;
        try {
            request = siteService.enableLetsEncrypt(siteId, authentication.getName());
        } catch (WildcardLetsEncryptException e) {
            redirect.addFlashAttribute("error", messages.get("msg.validation.wildcardLetsEncrypt"));
            return back;
        }
        if (request.issueNow()) {
            certificateService.submit(request.certificateId());
            redirect.addFlashAttribute("notice", messages.get("msg.cert.issueStarted"));
        } else {
            redirect.addFlashAttribute("notice",
                    messages.get(raw ? "msg.cert.rawIssueAfterApply" : "msg.cert.issueAfterApply"));
        }
        return back;
    }

    /** 원문 사이트에 발급된 인증서로 443 server 블록을 더한 초안을 만든다. 관리자만. */
    @PostMapping("/certs/letsencrypt/raw-https")
    public String rawHttps(@RequestParam long siteId, @RequestParam(defaultValue = "false") boolean redirectHttp,
                           Authentication authentication, RedirectAttributes redirect) {
        Site site = access.require(siteId, authentication);
        if (site.getType() != SiteType.RAW || !SiteAccess.isAdmin(authentication) || site.getCertificateId() == null) {
            throw new NotFoundException("site " + siteId);
        }
        CertificatePaths paths = certificateRepository.findById(site.getCertificateId())
                .map(TlsCertificate::paths).orElse(null);
        if (paths == null) {
            redirect.addFlashAttribute("error", messages.get("msg.cert.rawHttpsNotIssued"));
        } else if (siteService.addRawHttpsBlock(siteId, paths.certPath(), paths.keyPath(), redirectHttp,
                authentication.getName())) {
            redirect.addFlashAttribute("notice", messages.get("msg.cert.rawHttpsDrafted"));
        } else {
            redirect.addFlashAttribute("notice", messages.get("msg.cert.rawHttpsExists"));
        }
        return "redirect:/sites/" + siteId + "#https";
    }

    @PostMapping("/certs/upload")
    public String upload(@RequestParam String name, @RequestParam MultipartFile certificate,
                         @RequestParam MultipartFile privateKey, Authentication authentication,
                         RedirectAttributes redirect) throws IOException {
        try {
            Optional<String> failure = uploadService.upload(name,
                    new String(certificate.getBytes(), StandardCharsets.US_ASCII),
                    new String(privateKey.getBytes(), StandardCharsets.US_ASCII),
                    authentication.getName());
            if (failure.isPresent()) {
                redirect.addFlashAttribute("error", failure.get());
            } else {
                redirect.addFlashAttribute("notice", messages.get("msg.cert.uploaded"));
            }
        } catch (CertificateUploadService.InvalidCertificateException e) {
            redirect.addFlashAttribute("error", e.code() == null ? e.getMessage() : messages.get(e.code(), e.args()));
        }
        return "redirect:/certs";
    }

    /** 쓰지 않는 인증서를 지운다(010, 013). 관리자만. 확인은 화면에서 받는다. */
    @PostMapping("/certs/{id}/delete")
    public String delete(@PathVariable long id, @RequestParam(required = false) String tab,
                         Authentication authentication, RedirectAttributes redirect) {
        try {
            String name = deletion.delete(id, authentication.getName());
            redirect.addFlashAttribute("notice", messages.get("msg.cert.deleted", name));
        } catch (CertificateDeletion.BlockedException e) {
            redirect.addFlashAttribute("error", blockedMessage(e.blocker(), e.sites()));
        }
        return "redirect:/certs?tab=" + (ISSUED_TAB.equals(tab) ? ISSUED_TAB : PENDING_TAB);
    }

    private String blockedMessage(CertificateDeletion.Blocker blocker, List<String> sites) {
        return switch (blocker) {
            case REFERENCED -> messages.get("msg.cert.delete.referenced");
            case RUNNING -> messages.get("msg.cert.delete.running");
            case IN_USE -> messages.get("msg.cert.delete.inUse", String.join(", ", sites));
            case ADMIN_DOMAIN -> messages.get("msg.cert.delete.adminDomain");
        };
    }
}
