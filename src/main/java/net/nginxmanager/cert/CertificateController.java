package net.nginxmanager.cert;

import net.nginxmanager.common.Messages;
import net.nginxmanager.common.NotFoundException;
import net.nginxmanager.site.Site;
import net.nginxmanager.site.SiteRepository;
import net.nginxmanager.site.SiteService;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Controller
public class CertificateController {

    private final TlsCertificateRepository certificateRepository;
    private final SiteRepository siteRepository;
    private final CertificateService certificateService;
    private final CertificateUploadService uploadService;
    private final SiteService siteService;
    private final Clock clock;
    private final Messages messages;

    public CertificateController(TlsCertificateRepository certificateRepository, SiteRepository siteRepository,
                                 CertificateService certificateService, CertificateUploadService uploadService,
                                 SiteService siteService, Clock clock, Messages messages) {
        this.messages = messages;
        this.certificateRepository = certificateRepository;
        this.siteRepository = siteRepository;
        this.certificateService = certificateService;
        this.uploadService = uploadService;
        this.siteService = siteService;
        this.clock = clock;
    }

    @GetMapping("/certs")
    public String list(Model model) {
        Map<Long, List<String>> sitesByCert = siteRepository.findByCertificateIdIsNotNull().stream()
                .collect(Collectors.groupingBy(Site::getCertificateId,
                        Collectors.mapping(Site::getPrimaryDomain, Collectors.toList())));
        List<TlsCertificate> certificates = certificateRepository.findAllByOrderByNameAsc();
        model.addAttribute("certificates", certificates);
        model.addAttribute("sitesByCert", sitesByCert);
        model.addAttribute("running", certificates.stream()
                .filter(c -> certificateService.isRunning(c.getId())).map(TlsCertificate::getId).toList());
        model.addAttribute("now", clock.instant());
        return "certs/list";
    }

    @PostMapping("/certs/{id}/renew")
    public String renew(@PathVariable long id, RedirectAttributes redirect) {
        if (!certificateRepository.existsById(id)) {
            throw new NotFoundException("certificate " + id);
        }
        certificateService.submit(id);
        redirect.addFlashAttribute("notice", messages.get("msg.cert.renewStarted"));
        return "redirect:/certs";
    }

    /** 서비스 중인 폼 사이트에 Let's Encrypt HTTPS를 켜고 바로 발급을 시작한다. */
    @PostMapping("/certs/letsencrypt")
    public String letsEncrypt(@RequestParam long siteId, Authentication authentication, RedirectAttributes redirect) {
        long certificateId = siteService.enableLetsEncrypt(siteId, authentication.getName());
        certificateService.submit(certificateId);
        redirect.addFlashAttribute("notice", messages.get("msg.cert.issueStarted"));
        return "redirect:/sites/" + siteId;
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
}
