package net.nginxmanager.site;

import net.nginxmanager.cert.CertificatePaths;
import net.nginxmanager.cert.CertificateSource;
import net.nginxmanager.cert.TlsCertificate;
import net.nginxmanager.cert.TlsCertificateRepository;
import org.springframework.stereotype.Component;

/** 사이트에 연결된 인증서를 찾아 설정을 만든다. */
@Component
public class SiteRendering {

    private final SiteConfigRenderer renderer;
    private final TlsCertificateRepository certificateRepository;

    public SiteRendering(SiteConfigRenderer renderer, TlsCertificateRepository certificateRepository) {
        this.renderer = renderer;
        this.certificateRepository = certificateRepository;
    }

    public String render(Site site) {
        return render(site.getPrimaryDomain(), formOf(site), site.getCertificateId());
    }

    public String render(String primaryDomain, SiteForm form, Long certificateId) {
        CertificatePaths tls = form.httpsEnabled() && certificateId != null
                ? certificateRepository.findById(certificateId).map(TlsCertificate::paths).orElse(null)
                : null;
        return renderer.render(primaryDomain, form, tls);
    }

    public SiteForm formOf(Site site) {
        Long uploaded = site.getCertificateId() == null ? null : certificateRepository.findById(site.getCertificateId())
                .filter(c -> c.getSource() == CertificateSource.UPLOADED)
                .map(TlsCertificate::getId)
                .orElse(null);
        return site.toForm(uploaded);
    }
}
