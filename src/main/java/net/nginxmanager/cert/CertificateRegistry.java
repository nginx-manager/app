package net.nginxmanager.cert;

import net.nginxmanager.common.NotFoundException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;

/** 사이트 저장 때 연결할 인증서를 정한다. */
@Component
public class CertificateRegistry {

    private final TlsCertificateRepository certificateRepository;
    private final Clock clock;

    public CertificateRegistry(TlsCertificateRepository certificateRepository, Clock clock) {
        this.certificateRepository = certificateRepository;
        this.clock = clock;
    }

    /**
     * 사이트의 Let's Encrypt 인증서. 없으면 발급 대기 상태로 만들고, 도메인이 바뀌었으면 바로 다시 발급하게 한다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public long letsEncryptFor(String primaryDomain, List<String> domains) {
        TlsCertificate cert = certificateRepository.findByName(primaryDomain)
                .filter(c -> c.getSource() == CertificateSource.LETS_ENCRYPT)
                .orElseGet(() -> certificateRepository.save(TlsCertificate.letsEncrypt(primaryDomain, domains)));
        if (!cert.domainList().equals(domains)) {
            cert.requestReissue(domains, clock.instant());
        }
        return cert.getId();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public long requireUploaded(long certificateId) {
        TlsCertificate cert = certificateRepository.findById(certificateId)
                .filter(c -> c.getSource() == CertificateSource.UPLOADED)
                .orElseThrow(() -> new NotFoundException("uploaded certificate " + certificateId));
        return cert.getId();
    }
}
