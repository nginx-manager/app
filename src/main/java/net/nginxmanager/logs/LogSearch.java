package net.nginxmanager.logs;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 로그를 파일 끝에서부터 거꾸로 읽어 최근 줄부터 찾는다. 큰 로그도 끝부분만 읽고, 읽는 양에 상한을 둔다.
 */
public final class LogSearch {

    static final int CHUNK = 64 * 1024;
    /** 줄바꿈 없이 이보다 긴 조각은 잘라 낸다. */
    static final int MAX_LINE = 64 * 1024;

    /**
     * @param lines     찾은 줄. 최근 줄이 앞에 온다
     * @param exhausted 읽기 상한에 걸려 파일 앞부분을 다 보지 못했다
     * @param missing   파일이 없다(아직 요청이 없었거나 경로가 다르다)
     */
    public record Result(List<String> lines, boolean exhausted, boolean missing) {
    }

    private LogSearch() {
    }

    public static Result search(Path file, String query, int limit, long maxBytes) throws IOException {
        if (!Files.isRegularFile(file)) {
            return new Result(List.of(), false, true);
        }
        String needle = query == null || query.isBlank() ? null : query.toLowerCase(Locale.ROOT);
        List<String> found = new ArrayList<>();
        try (RandomAccessFile in = new RandomAccessFile(file.toFile(), "r")) {
            long pos = in.length();
            long scanned = 0;
            byte[] rest = new byte[0];
            while (pos > 0 && found.size() < limit && scanned < maxBytes) {
                int n = (int) Math.min(CHUNK, pos);
                pos -= n;
                byte[] data = new byte[n + rest.length];
                in.seek(pos);
                in.readFully(data, 0, n);
                System.arraycopy(rest, 0, data, n, rest.length);
                int end = data.length;
                for (int i = data.length - 1; i >= 0 && found.size() < limit; i--) {
                    if (data[i] == '\n') {
                        collect(data, i + 1, end, needle, found);
                        end = i;
                    }
                }
                rest = Arrays.copyOfRange(data, Math.max(0, end - MAX_LINE), end);
                scanned += n;
            }
            if (pos == 0 && found.size() < limit) {
                collect(rest, 0, rest.length, needle, found);
            }
            return new Result(List.copyOf(found), pos > 0 && found.size() < limit, false);
        }
    }

    private static void collect(byte[] data, int from, int to, String needle, List<String> found) {
        if (to <= from) {
            return;
        }
        String line = new String(data, from, to - from, StandardCharsets.UTF_8);
        if (line.endsWith("\r")) {
            line = line.substring(0, line.length() - 1);
        }
        if (line.isEmpty()) {
            return;
        }
        if (needle == null || line.toLowerCase(Locale.ROOT).contains(needle)) {
            found.add(line);
        }
    }
}
