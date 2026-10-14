package net.nginxmanager.cert;

import net.nginxmanager.runtime.NginxRuntimeProperties;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.Locale;

/**
 * 인증서 파일을 &lt;설정 루트&gt;/certs/&lt;이름&gt;/&lt;시각&gt;/ 에 둔다. 발급·갱신마다 새 디렉터리를 만들어
 * 설정 경로만 바꾸면 되게 하고, 적용이 실패하면 새 디렉터리를 지운다.
 */
@Component
public class CertificateFiles {

    static final String FULLCHAIN = "fullchain.pem";
    static final String PRIVKEY = "privkey.pem";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC);
    /** 되돌리기에 쓸 수 있도록 지금 것 말고 이전 것을 몇 개 남긴다. */
    private static final int KEEP_PREVIOUS = 2;

    private final Path root;
    private final Clock clock;

    public CertificateFiles(NginxRuntimeProperties properties, Clock clock) {
        this.root = properties.configRoot().resolve("certs").toAbsolutePath().normalize();
        this.clock = clock;
    }

    public CertificatePaths write(String name, String fullchainPem, String privateKeyPem) {
        Path dir = root.resolve(safeName(name)).resolve(STAMP.format(clock.instant()));
        try {
            Files.createDirectories(dir);
            Path cert = dir.resolve(FULLCHAIN);
            Path key = dir.resolve(PRIVKEY);
            Files.writeString(cert, fullchainPem, StandardCharsets.US_ASCII);
            Acme4jClient.createPrivate(key);
            Files.writeString(key, privateKeyPem, StandardCharsets.US_ASCII);
            return new CertificatePaths(cert.toString(), key.toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 반영되지 못한 새 인증서 디렉터리를 지운다. */
    public void discard(CertificatePaths paths) {
        deleteTree(Path.of(paths.certPath()).getParent());
    }

    /**
     * 지금 쓰는 디렉터리와 바로 이전 몇 개만 남긴다. 설정 파일·초안·되돌릴 수 있는 버전이 아직 가리키는 디렉터리는
     * 오래되었어도 남긴다(지우면 nginx가 시작하지 못하거나 되돌리기가 검증에서 실패한다).
     *
     * @param referenced 디렉터리 경로를 받아 어딘가에서 아직 쓰는지 알려 준다
     */
    public void prune(String name, CertificatePaths current, Predicate<String> referenced) {
        Path base = root.resolve(safeName(name));
        Path keep = Path.of(current.certPath()).getParent();
        if (!Files.isDirectory(base)) {
            return;
        }
        try (Stream<Path> dirs = Files.list(base)) {
            List<Path> old = dirs.filter(Files::isDirectory).filter(d -> !d.equals(keep))
                    .sorted(Comparator.reverseOrder()).toList();
            old.stream().skip(KEEP_PREVIOUS)
                    .filter(dir -> !referenced.test(dir.toString() + "/"))
                    .forEach(CertificateFiles::deleteTree);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 이 인증서의 어느 디렉터리(이전 발급분 포함)를 가리키는 경로든 새 경로로 바꾼다. 되돌리기로 더 오래된 인증서를
     * 가리키게 된 설정도 갱신 때 함께 새 인증서로 옮겨 간다.
     */
    public String repoint(String content, String name, CertificatePaths fresh) {
        String base = Pattern.quote(root.resolve(safeName(name)).toString() + "/") + "[^/\\s;\"']+/";
        return content
                .replaceAll(base + Pattern.quote(FULLCHAIN), Matcher.quoteReplacement(fresh.certPath()))
                .replaceAll(base + Pattern.quote(PRIVKEY), Matcher.quoteReplacement(fresh.keyPath()));
    }

    /** 인증서 파일 경로를 이름·시각과 상관없는 같은 글자로 바꾼다. 경로만 다른 두 설정을 같다고 볼 때 쓴다. */
    public String maskPaths(String content) {
        return content.replaceAll(Pattern.quote(root.toString() + "/") + "[^\\s;\"']+/(" + Pattern.quote(FULLCHAIN)
                + "|" + Pattern.quote(PRIVKEY) + ")", "<certificate>/$1");
    }

    public X509Certificate readLeaf(CertificatePaths paths) {
        try {
            return parseChain(Files.readString(Path.of(paths.certPath()))).getFirst();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static List<X509Certificate> parseChain(String pem) {
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            List<X509Certificate> chain = factory.generateCertificates(
                            new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII))).stream()
                    .map(X509Certificate.class::cast).toList();
            if (chain.isEmpty()) {
                throw new IllegalArgumentException("인증서가 없습니다.");
            }
            return chain;
        } catch (CertificateException e) {
            throw new IllegalArgumentException("PEM 인증서를 읽을 수 없습니다: " + e.getMessage(), e);
        }
    }

    static String safeName(String name) {
        String safe = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9.-]", "-");
        if (safe.isBlank() || safe.startsWith(".")) {
            throw new IllegalArgumentException("invalid certificate name: " + name);
        }
        return safe;
    }

    private static void deleteTree(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
