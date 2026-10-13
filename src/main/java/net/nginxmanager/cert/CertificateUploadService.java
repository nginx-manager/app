package net.nginxmanager.cert;

import net.nginxmanager.audit.AuditAction;
import net.nginxmanager.audit.AuditEvent;
import net.nginxmanager.config.VersionSource;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.StringReader;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Locale;

/**
 * 가지고 있는 인증서와 키를 올린다. 키가 인증서와 맞는지 서명으로 확인하고, 키는 파일로만 저장한다(화면에 다시 보이지 않는다).
 * 같은 이름으로 다시 올리면 교체하고, 그 인증서를 쓰는 설정을 적용 절차로 바꾼다.
 */
@Service
public class CertificateUploadService {

    /** 올린 인증서를 쓸 수 없다. code가 있으면 화면은 그 메시지(args)를 사용자의 언어로 보여 준다. */
    public static class InvalidCertificateException extends RuntimeException {
        private final String code;
        private final transient Object[] args;

        public InvalidCertificateException(String message) {
            this(null, message);
        }

        public InvalidCertificateException(String code, String message, Object... args) {
            super(message);
            this.code = code;
            this.args = args;
        }

        public String code() {
            return code;
        }

        public Object[] args() {
            return args.clone();
        }
    }

    /** SEC1 형식의 EC 키(BEGIN EC PRIVATE KEY)는 JDK만으로 읽을 수 없어 BouncyCastle로 읽고 확인한다. */
    private static final Provider BC = new BouncyCastleProvider();

    private final TlsCertificateRepository certificateRepository;
    private final CertificateFiles certificateFiles;
    private final CertificateService certificateService;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public CertificateUploadService(TlsCertificateRepository certificateRepository, CertificateFiles certificateFiles,
                                    CertificateService certificateService, TransactionTemplate tx,
                                    ApplicationEventPublisher events, Clock clock) {
        this.certificateRepository = certificateRepository;
        this.certificateFiles = certificateFiles;
        this.certificateService = certificateService;
        this.tx = tx;
        this.events = events;
        this.clock = clock;
    }

    /** @return 실패 사유. 성공하면 비어 있다 */
    public Optional<String> upload(String name, String chainPem, String keyPem, String actor) {
        String safeName;
        try {
            safeName = CertificateFiles.safeName(name.trim());
        } catch (IllegalArgumentException e) {
            throw new InvalidCertificateException("msg.cert.upload.badName", "이름은 영문 소문자, 숫자, . - 만 쓸 수 있습니다.");
        }
        List<X509Certificate> chain;
        try {
            chain = CertificateFiles.parseChain(chainPem);
        } catch (IllegalArgumentException e) {
            throw new InvalidCertificateException(e.getMessage());
        }
        X509Certificate leaf = chain.getFirst();
        PrivateKey key = parseKey(keyPem);
        requireMatch(leaf, key);
        Instant now = clock.instant();
        if (leaf.getNotAfter().toInstant().isBefore(now)) {
            throw new InvalidCertificateException("msg.cert.upload.expired", "이미 만료된 인증서입니다(" + leaf.getNotAfter().toInstant() + ").",
                    leaf.getNotAfter().toInstant().toString());
        }

        Optional<TlsCertificate> existing = certificateRepository.findByName(safeName);
        if (existing.isPresent() && existing.get().getSource() != CertificateSource.UPLOADED) {
            throw new InvalidCertificateException("msg.cert.upload.nameTaken", "Let's Encrypt 인증서와 같은 이름입니다. 다른 이름을 써 주세요.");
        }
        Long id = existing.map(TlsCertificate::getId).orElseGet(() -> tx.execute(status ->
                certificateRepository.save(TlsCertificate.uploaded(safeName, domainsOf(leaf))).getId()));

        CertificatePaths fresh = certificateFiles.write(safeName, chainPem.strip() + "\n", keyPem.strip() + "\n");
        CertificateService.Target target = tx.execute(status -> certificateService.targetOf(certificateService.load(id)));
        Optional<String> failure = certificateService.install(id, target, fresh, leaf.getNotBefore().toInstant(),
                leaf.getNotAfter().toInstant(), null, VersionSource.CERT_RENEW, actor, "인증서 업로드: " + safeName);
        if (failure.isEmpty()) {
            events.publishEvent(AuditEvent.success(actor, AuditAction.CERT_UPLOAD, "cert:" + safeName));
        }
        return failure;
    }

    private static PrivateKey parseKey(String pem) {
        try (PEMParser parser = new PEMParser(new StringReader(pem))) {
            Object object = parser.readObject();
            JcaPEMKeyConverter converter = new JcaPEMKeyConverter().setProvider(BC);
            if (object instanceof PEMKeyPair pair) {
                return converter.getPrivateKey(pair.getPrivateKeyInfo());
            }
            if (object instanceof PrivateKeyInfo info) {
                return converter.getPrivateKey(info);
            }
        } catch (IOException e) {
            throw new InvalidCertificateException("msg.cert.upload.unreadableKey", "개인 키를 읽을 수 없습니다: " + e.getMessage(),
                    String.valueOf(e.getMessage()));
        }
        throw new InvalidCertificateException("msg.cert.upload.notPlainKey", "암호가 걸리지 않은 PEM 개인 키가 아닙니다.");
    }

    /** 키로 서명하고 인증서의 공개 키로 확인해, 둘이 짝인지 본다. */
    private static void requireMatch(X509Certificate cert, PrivateKey key) {
        try {
            byte[] data = new byte[32];
            new SecureRandom().nextBytes(data);
            String algorithm = switch (key.getAlgorithm()) {
                case "RSA" -> "SHA256withRSA";
                case "EC", "ECDSA" -> "SHA256withECDSA";
                case "Ed25519", "EdDSA" -> "Ed25519";
                default -> throw new InvalidCertificateException("msg.cert.upload.keyType", "지원하지 않는 키 종류입니다: " + key.getAlgorithm(),
                        key.getAlgorithm());
            };
            Signature signer = Signature.getInstance(algorithm, BC);
            signer.initSign(key);
            signer.update(data);
            byte[] signature = signer.sign();
            Signature verifier = Signature.getInstance(algorithm, BC);
            verifier.initVerify(cert.getPublicKey());
            verifier.update(data);
            if (!verifier.verify(signature)) {
                throw new InvalidCertificateException("msg.cert.upload.keyMismatch", "개인 키가 인증서와 맞지 않습니다.");
            }
        } catch (GeneralSecurityException e) {
            throw new InvalidCertificateException("msg.cert.upload.keyMismatch", "개인 키가 인증서와 맞지 않습니다.");
        }
    }

    static List<String> domainsOf(X509Certificate cert) {
        List<String> domains = new ArrayList<>();
        try {
            Collection<List<?>> names = cert.getSubjectAlternativeNames();
            if (names != null) {
                for (List<?> entry : names) {
                    if (entry.size() >= 2 && Integer.valueOf(2).equals(entry.get(0))) {
                        domains.add(entry.get(1).toString().toLowerCase(Locale.ROOT));
                    }
                }
            }
        } catch (CertificateParsingException e) {
            // SAN이 없으면 CN을 쓴다
        }
        if (domains.isEmpty()) {
            String subject = cert.getSubjectX500Principal().getName();
            for (String part : subject.split(",")) {
                if (part.trim().startsWith("CN=")) {
                    domains.add(part.trim().substring(3).toLowerCase(Locale.ROOT));
                }
            }
        }
        return domains.isEmpty() ? List.of("(도메인 없음)") : domains;
    }
}
