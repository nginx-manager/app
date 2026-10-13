package net.nginxmanager.logs;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 로그 파일에 새로 붙은 줄을 읽는다(tail -f). 끝나지 않은 줄은 다음 번에 읽는다.
 * 파일이 줄어들면(로그 교체) 처음부터 다시 읽는다.
 */
public final class LogCursor {

    static final int MAX_READ = 256 * 1024;

    private final Path file;
    private long position;

    /** 지금 파일 끝에서 시작한다. */
    public LogCursor(Path file) throws IOException {
        this.file = file;
        this.position = Files.isRegularFile(file) ? Files.size(file) : 0;
    }

    public List<String> readNewLines() throws IOException {
        if (!Files.isRegularFile(file)) {
            position = 0;
            return List.of();
        }
        try (RandomAccessFile in = new RandomAccessFile(file.toFile(), "r")) {
            long length = in.length();
            if (length < position) {
                position = 0;
            }
            if (length == position) {
                return List.of();
            }
            int n = (int) Math.min(MAX_READ, length - position);
            byte[] data = new byte[n];
            in.seek(position);
            in.readFully(data);
            int lastNewline = -1;
            for (int i = n - 1; i >= 0; i--) {
                if (data[i] == '\n') {
                    lastNewline = i;
                    break;
                }
            }
            if (lastNewline < 0) {
                // 한 번에 읽는 양보다 긴 줄이면 읽은 만큼 내보낸다
                if (n == MAX_READ) {
                    position += n;
                    return List.of(new String(data, StandardCharsets.UTF_8));
                }
                return List.of();
            }
            position += lastNewline + 1;
            List<String> lines = new ArrayList<>();
            for (String line : new String(data, 0, lastNewline, StandardCharsets.UTF_8).split("\n", -1)) {
                lines.add(line.endsWith("\r") ? line.substring(0, line.length() - 1) : line);
            }
            return lines;
        }
    }
}
