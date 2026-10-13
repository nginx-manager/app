package net.nginxmanager.config;

import net.nginxmanager.runtime.NginxRuntimeProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

/** 설정 루트 아래 파일 읽기·쓰기. 경로는 항상 루트 안으로 제한한다. */
@Component
public class ConfigFileStore {

    private final Path root;

    public ConfigFileStore(NginxRuntimeProperties properties) {
        this.root = properties.configRoot().toAbsolutePath().normalize();
    }

    public Path root() {
        return root;
    }

    /** 파일 내용. 없으면 null. */
    public String read(String relativePath) {
        try {
            return Files.readString(resolve(relativePath), StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 임시 파일에 쓴 뒤 옮겨, nginx가 반쯤 쓴 파일을 읽지 않게 한다. content가 null이면 지운다. */
    public void write(String relativePath, String content) {
        Path target = resolve(relativePath);
        try {
            if (content == null) {
                Files.deleteIfExists(target);
                return;
            }
            Files.createDirectories(target.getParent());
            Path temp = Files.createTempFile(target.getParent(), ".nm-", ".tmp");
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 루트 아래의 *.conf 파일 상대 경로 목록. */
    public List<String> listConfFiles() {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".conf"))
                    .map(p -> root.relativize(p).toString())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Path resolve(String relativePath) {
        Path resolved = root.resolve(relativePath).normalize();
        if (!resolved.startsWith(root) || resolved.equals(root)) {
            throw new IllegalArgumentException("path escapes config root: " + relativePath);
        }
        return resolved;
    }

    public static String hash(String content) {
        if (content == null) {
            return null;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
