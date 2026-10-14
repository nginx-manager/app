package net.nginxmanager.cert;

import org.shredzone.acme4j.Account;
import org.shredzone.acme4j.AccountBuilder;
import org.shredzone.acme4j.Authorization;
import org.shredzone.acme4j.Certificate;
import org.shredzone.acme4j.Order;
import org.shredzone.acme4j.Session;
import org.shredzone.acme4j.Status;
import org.shredzone.acme4j.challenge.Http01Challenge;
import org.shredzone.acme4j.exception.AcmeException;
import org.shredzone.acme4j.exception.AcmeRateLimitedException;
import org.shredzone.acme4j.util.KeyPairUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/** acme4j로 Let's Encrypt(또는 설정한 ACME 서버)에서 HTTP-01 챌린지로 인증서를 받는다. */
@Component
public class Acme4jClient implements AcmeClient {

    private static final Logger log = LoggerFactory.getLogger(Acme4jClient.class);

    private final AcmeProperties properties;
    private final AcmeSettingsRepository settingsRepository;

    public Acme4jClient(AcmeProperties properties, AcmeSettingsRepository settingsRepository) {
        this.properties = properties;
        this.settingsRepository = settingsRepository;
    }

    @Override
    public Issued issue(List<String> domains, X509Certificate replaces) throws AcmeFailure {
        AcmeSettings settings = settingsRepository.findFirstByOrderByIdAsc().orElseGet(AcmeSettings::new);
        String directory = directory(settings);
        List<Path> challengeFiles = new ArrayList<>();
        try {
            Session session = new Session(directory);
            AccountBuilder builder = new AccountBuilder().agreeToTermsOfService().useKeyPair(accountKey(directory));
            if (settings.getContactEmail() != null) {
                builder.addEmail(settings.getContactEmail());
            }
            Account account = builder.create(session);

            Order order;
            if (replaces != null) {
                try {
                    order = account.newOrder().domains(domains).replaces(replaces).create();
                } catch (AcmeRateLimitedException e) {
                    throw e;
                } catch (AcmeException e) {
                    // 지금 인증서가 다른 CA(스테이징↔운영)나 다른 계정에서 받은 것이면 replaces를 거절한다. 그냥 새로 받는다
                    log.info("order with replaces rejected ({}), ordering without it", e.getMessage());
                    order = account.newOrder().domains(domains).create();
                }
            } else {
                order = account.newOrder().domains(domains).create();
            }
            for (Authorization authorization : order.getAuthorizations()) {
                if (authorization.getStatus() == Status.VALID) {
                    continue;
                }
                Http01Challenge challenge = authorization.findChallenge(Http01Challenge.class)
                        .orElseThrow(() -> new AcmeFailure("HTTP-01 챌린지를 쓸 수 없는 도메인입니다: "
                                + authorization.getIdentifier().getDomain(), null, null));
                challengeFiles.add(writeChallenge(challenge.getToken(), challenge.getAuthorization()));
                challenge.trigger();
                Status status = authorization.waitForCompletion(properties.timeout());
                if (status != Status.VALID) {
                    String reason = challenge.getError().map(Object::toString).orElse(status.name());
                    throw new AcmeFailure("도메인 확인 실패(" + authorization.getIdentifier().getDomain() + "): "
                            + reason + ". DNS가 이 서버를 가리키고 80 포트가 열려 있는지 확인하세요.", null, null);
                }
            }

            KeyPair domainKey = KeyPairUtils.createKeyPair(2048);
            order.execute(domainKey);
            Status status = order.waitForCompletion(properties.timeout());
            if (status != Status.VALID) {
                throw new AcmeFailure("인증서 발급 실패: " + order.getError().map(Object::toString).orElse(status.name()),
                        null, null);
            }
            Certificate certificate = order.getCertificate();
            StringWriter chain = new StringWriter();
            certificate.writeCertificate(chain);
            StringWriter key = new StringWriter();
            KeyPairUtils.writeKeyPair(domainKey, key);
            return new Issued(chain.toString(), key.toString(), certificate.getCertificate(), renewalWindow(certificate));
        } catch (AcmeRateLimitedException e) {
            throw new AcmeFailure("발급 한도에 걸렸습니다: " + e.getMessage(), e.getRetryAfter().orElse(null), e);
        } catch (AcmeException e) {
            throw new AcmeFailure("ACME 서버 오류: " + e.getMessage(), null, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AcmeFailure("발급을 기다리다 중단됐습니다.", null, e);
        } catch (IOException e) {
            throw new AcmeFailure("인증서를 저장하지 못했습니다: " + e.getMessage(), null, e);
        } finally {
            challengeFiles.forEach(Acme4jClient::deleteQuietly);
        }
    }

    private static Optional<RenewalWindow> renewalWindow(Certificate certificate) {
        try {
            if (certificate.hasRenewalInfo()) {
                var info = certificate.getRenewalInfo();
                return Optional.of(new RenewalWindow(info.getSuggestedWindowStart(), info.getSuggestedWindowEnd()));
            }
        } catch (RuntimeException e) {
            log.info("ACME renewal info unavailable: {}", e.getMessage());
        }
        return Optional.empty();
    }

    private Path writeChallenge(String token, String content) throws IOException {
        Path dir = properties.webroot().resolve(".well-known/acme-challenge");
        Files.createDirectories(dir);
        Path file = dir.resolve(token);
        Files.writeString(file, content, StandardCharsets.US_ASCII);
        return file;
    }

    /** 환경 변수로 정한 ACME 서버가 있으면 그것을, 없으면 화면에서 고른 운영/스테이징을 쓴다(FR-030). */
    String directory(AcmeSettings settings) {
        return properties.directoryUrl() != null && !properties.directoryUrl().isBlank()
                ? properties.directoryUrl()
                : settings.getEnvironment().directory();
    }

    /** ACME 서버마다 계정 키 하나를 볼륨에 둔다. 없으면 만든다. */
    private KeyPair accountKey(String directory) throws IOException {
        Path dir = properties.accountDir();
        Files.createDirectories(dir);
        Path file = dir.resolve("account-" + sha256(directory).substring(0, 12) + ".pem");
        if (Files.exists(file)) {
            try (var reader = Files.newBufferedReader(file)) {
                return KeyPairUtils.readKeyPair(reader);
            }
        }
        KeyPair keyPair = KeyPairUtils.createKeyPair(2048);
        createPrivate(file);
        try (Writer writer = Files.newBufferedWriter(file)) {
            KeyPairUtils.writeKeyPair(keyPair, writer);
        }
        return keyPair;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 내용을 쓰기 전에 소유자만 읽을 수 있는 빈 파일을 만든다(쓰는 동안에도 다른 사용자가 읽지 못하게). */
    static void createPrivate(Path file) throws IOException {
        Files.deleteIfExists(file);
        try {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException e) {
            // POSIX 권한이 없는 파일 시스템
            Files.createFile(file);
        }
    }

    static void restrict(Path file) {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException e) {
            // POSIX 권한이 없는 파일 시스템
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("could not delete challenge file {}", file);
        }
    }
}
