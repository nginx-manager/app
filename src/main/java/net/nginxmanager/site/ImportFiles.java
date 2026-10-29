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
import java.nio.file.FileSystems;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

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

    /** 패턴 검사 결과. error가 있으면 files는 비어 있다. */
    public record Scan(String pattern, List<Found> files, String errorKey, String errorArg, boolean truncated) {
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
        boolean truncated = false;
        int depth = absolute.contains("**") ? MAX_DEPTH : depthOf(absolute) - depthOf(base.toString());
        try (Stream<Path> paths = Files.walk(base, Math.max(depth, 0), FileVisitOption.FOLLOW_LINKS)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                if (!Files.isRegularFile(path) || !matcher.matches(path)) {
                    continue;
                }
                Optional<Found> file = resolve(path.toString());
                if (file.isEmpty() || isReserved(file.get())) {
                    continue;
                }
                if (found.size() >= MAX_MATCHES) {
                    truncated = true;
                    break;
                }
                found.add(file.get());
            }
        } catch (IOException | UncheckedIOException e) {
            return new Scan(pattern, List.of(), "import.error.notVisible", base.toString(), false);
        }
        found.sort(java.util.Comparator.comparing(Found::path));
        return new Scan(pattern, found, null, null, truncated);
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
