package net.nginxmanager.site;

import net.nginxmanager.config.ConfigFileStore;
import net.nginxmanager.config.NewFilePath;
import net.nginxmanager.stream.StreamPaths;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

/**
 * 가져오기 패턴에 맞는 파일 찾기와 읽기. 설정 루트와 허용한 디렉터리({@link ImportProperties#roots}) 안의 파일만 다룬다
 * (관리도구 컨테이너의 DB·토큰 같은 파일을 화면으로 읽어 내지 못하게). 심볼릭 링크는 실제 위치로 판단한다.
 */
@Component
public class ImportFiles {

    /** 가져올 수 있는 파일 크기 상한. */
    public static final int MAX_BYTES = 1024 * 1024;
    /** 패턴 하나가 찾는 파일 수 상한. */
    static final int MAX_MATCHES = 200;
    private static final int MAX_DEPTH = 12;

    private final ConfigFileStore store;
    private final ImportProperties properties;

    public ImportFiles(ConfigFileStore store, ImportProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    /**
     * 찾은 파일.
     *
     * @param path         절대 경로
     * @param relativePath 설정 루트 안이면 루트 기준 경로, 밖이면 null
     */
    public record Found(String path, String relativePath) {

        public boolean inConfigRoot() {
            return relativePath != null;
        }
    }

    /**
     * 패턴 검사 결과. error가 있으면 files는 비어 있다.
     *
     * @param denied 권한이 없어 열지 못한 디렉터리와 읽지 못한 파일(관리도구는 UID 10101로 돈다)
     */
    public record Scan(String pattern, List<Found> files, String errorKey, String errorArg, boolean truncated,
                       List<String> denied) {

        Scan(String pattern, List<Found> files, String errorKey, String errorArg, boolean truncated) {
            this(pattern, files, errorKey, errorArg, truncated, List.of());
        }
    }

    /** 화면에 보일 허용 디렉터리(설정 루트 포함). */
    public List<String> roots() {
        List<String> roots = new ArrayList<>();
        roots.add(store.root().toString());
        properties.roots().stream().map(Path::toString).filter(r -> !roots.contains(r)).forEach(roots::add);
        return roots;
    }

    /** 상대 패턴은 설정 루트 기준으로 바꾼 절대 패턴. */
    public String absolute(String pattern) {
        String trimmed = pattern.strip();
        return trimmed.startsWith("/") ? trimmed : store.root().resolve(trimmed).toString();
    }

    public Scan scan(String pattern) {
        if (pattern == null || pattern.isBlank() || pattern.length() > 1024 || pattern.contains("\0")) {
            return new Scan(pattern, List.of(), "import.error.pattern", pattern, false);
        }
        String absolute = absolute(pattern);
        if (absolute.contains("/../") || absolute.endsWith("/..")) {
            return new Scan(pattern, List.of(), "import.error.pattern", pattern, false);
        }
        Path base = baseOf(absolute);
        if (allowedRoot(base).isEmpty()) {
            return new Scan(pattern, List.of(), "import.error.outsideRoots", String.join(", ", roots()), false);
        }
        if (!Files.isDirectory(base) && !Files.isRegularFile(base)) {
            return new Scan(pattern, List.of(), "import.error.notVisible", base.toString(), false);
        }
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + absolute);
        List<Found> found = new ArrayList<>();
        List<String> denied = new ArrayList<>();
        boolean[] truncated = {false};
        int depth = absolute.contains("**") ? MAX_DEPTH : depthOf(absolute) - depthOf(base.toString());
        try {
            Files.walkFileTree(base, EnumSet.of(FileVisitOption.FOLLOW_LINKS), Math.max(depth, 0), new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) {
                    if (!attrs.isRegularFile() || !matcher.matches(path)) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (!Files.isReadable(path)) {
                        addDenied(denied, path);
                        return FileVisitResult.CONTINUE;
                    }
                    Optional<Found> file = resolve(path.toString());
                    if (file.isEmpty() || isReserved(file.get())) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (found.size() >= MAX_MATCHES) {
                        truncated[0] = true;
                        return FileVisitResult.TERMINATE;
                    }
                    found.add(file.get());
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path path, IOException e) {
                    // 한 사용자의 디렉터리를 못 열어도 나머지는 계속 찾는다
                    if (e instanceof AccessDeniedException && couldMatch(absolute, path)) {
                        addDenied(denied, path);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException | UncheckedIOException e) {
            return new Scan(pattern, List.of(), "import.error.notVisible", base.toString(), false);
        }
        found.sort(java.util.Comparator.comparing(Found::path));
        denied.sort(null);
        return new Scan(pattern, found, null, null, truncated[0], denied);
    }

    private static void addDenied(List<String> denied, Path path) {
        if (denied.size() < MAX_MATCHES) {
            denied.add(path.toString());
        }
    }

    /** 패턴에 맞는 파일이 이 디렉터리 아래에 있을 수 있는지. */
    static boolean couldMatch(String absolutePattern, Path dir) {
        String[] pattern = absolutePattern.substring(1).split("/");
        String[] parts = dir.toString().substring(1).split("/");
        for (int i = 0; i < parts.length; i++) {
            if (i >= pattern.length - 1) {
                return false;
            }
            if (pattern[i].contains("**")) {
                return true;
            }
            if (!FileSystems.getDefault().getPathMatcher("glob:" + pattern[i]).matches(Path.of(parts[i]))) {
                return false;
            }
        }
        return true;
    }

    /** 경로 하나를 확인한다. 허용한 디렉터리 밖이거나 파일이 아니면 비어 있다. */
    public Optional<Found> resolve(String path) {
        if (path == null || path.isBlank() || path.contains("\0")) {
            return Optional.empty();
        }
        Path real;
        try {
            real = Path.of(absolute(path)).toRealPath();
        } catch (IOException | java.nio.file.InvalidPathException e) {
            return Optional.empty();
        }
        if (!Files.isRegularFile(real)) {
            return Optional.empty();
        }
        Optional<Path> root = allowedRoot(real);
        if (root.isEmpty()) {
            return Optional.empty();
        }
        Path configRoot = realOf(store.root());
        String relative = real.startsWith(configRoot) ? configRoot.relativize(real).toString() : null;
        return Optional.of(new Found(real.toString(), relative));
    }

    /**
     * 파일 내용(UTF-8).
     *
     * @throws IllegalArgumentException 크기 상한을 넘거나 UTF-8이 아니면
     */
    public String read(Found file) {
        try {
            Path path = Path.of(file.path());
            if (Files.size(path) > MAX_BYTES) {
                throw new IllegalArgumentException("import.error.tooLarge");
            }
            return decode(Files.readAllBytes(path));
        } catch (AccessDeniedException e) {
            throw new IllegalArgumentException("import.error.denied");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** @throws IllegalArgumentException UTF-8이 아니면 */
    public static String decode(byte[] bytes) {
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            return text.startsWith("﻿") ? text.substring(1) : text;
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("import.error.encoding");
        }
    }

    private static final java.util.regex.Pattern PATH_DIRECTIVE = java.util.regex.Pattern.compile(
            "(?m)(?:^|[;{\\s])(?:ssl_certificate|ssl_certificate_key|ssl_trusted_certificate|ssl_dhparam|include|root|alias)"
                    + "\\s+\"?(/[^;\\s\"]+)");

    /**
     * 설정이 가리키지만 컨테이너에서 보이지 않는 파일·디렉터리(예: Certbot의 /etc/letsencrypt/...). 그대로 적용하면
     * nginx -t가 실패한다. 변수나 와일드카드가 든 경로는 확인하지 않는다.
     */
    public static List<String> missingReferences(String content) {
        java.util.LinkedHashSet<String> missing = new java.util.LinkedHashSet<>();
        java.util.regex.Matcher m = PATH_DIRECTIVE.matcher(content);
        while (m.find()) {
            String path = m.group(1);
            if (path.contains("$") || path.contains("*") || path.contains("?")) {
                continue;
            }
            try {
                if (!Files.exists(Path.of(path))) {
                    missing.add(path);
                }
            } catch (java.nio.file.InvalidPathException ignored) {
                // 경로가 아니면 넘어간다
            }
        }
        return List.copyOf(missing);
    }

    /** 설정 루트 안에서 관리도구가 이미 다루는 곳: 사이트·스트림 파일, nginx.conf, 인증서·비밀번호 디렉터리. */
    static boolean isReserved(Found file) {
        String relative = file.relativePath();
        if (relative == null) {
            return false;
        }
        return SitePaths.domainOf(relative) != null || relative.startsWith(SitePaths.ENABLED_DIR)
                || relative.startsWith(SitePaths.DISABLED_DIR) || relative.startsWith(StreamPaths.DIR)
                || NewFilePath.isProtected(relative) || relative.startsWith("certs/") || relative.startsWith(Htpasswd.DIR);
    }

    private Optional<Path> allowedRoot(Path path) {
        List<Path> roots = new ArrayList<>();
        roots.add(realOf(store.root()));
        properties.roots().forEach(r -> roots.add(realOf(r)));
        Path normalized = path.toAbsolutePath().normalize();
        return roots.stream().filter(normalized::startsWith).findFirst();
    }

    private static Path realOf(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return path.toAbsolutePath().normalize();
        }
    }

    /** glob 문자가 나오기 전까지의 디렉터리. */
    static Path baseOf(String absolutePattern) {
        String[] parts = absolutePattern.split("/");
        StringBuilder base = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (part.chars().anyMatch(c -> "*?[{".indexOf(c) >= 0)) {
                break;
            }
            base.append('/').append(part);
        }
        return Path.of(base.isEmpty() ? "/" : base.toString());
    }

    private static int depthOf(String path) {
        return (int) path.chars().filter(c -> c == '/').count();
    }
}
