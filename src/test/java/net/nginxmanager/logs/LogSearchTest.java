package net.nginxmanager.logs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class LogSearchTest {

    @TempDir
    Path dir;

    private Path log(String content) throws IOException {
        Path file = dir.resolve("a.access.log");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void returnsNewestMatchingLinesFirstIgnoringCase() throws IOException {
        Path file = log("GET /a 200\nGET /api 502\nPOST /b 200\nget /API/v2 502\n");

        LogSearch.Result result = LogSearch.search(file, "/api", 10, Long.MAX_VALUE);

        assertThat(result.lines()).containsExactly("get /API/v2 502", "GET /api 502");
        assertThat(result.exhausted()).isFalse();
        assertThat(result.missing()).isFalse();
    }

    @Test
    void blankQueryReturnsLatestLinesUpToLimit() throws IOException {
        Path file = log("1\n2\n3\n4\n5");

        assertThat(LogSearch.search(file, " ", 3, Long.MAX_VALUE).lines()).containsExactly("5", "4", "3");
    }

    @Test
    void linesCrossingChunkBoundariesStayWhole() throws IOException {
        // 줄 길이가 청크 크기와 맞지 않아 여러 줄이 청크 경계에 걸친다
        String content = IntStream.range(0, 20_000)
                .mapToObj(i -> "line-" + i + " " + "x".repeat(i % 37))
                .collect(Collectors.joining("\r\n", "", "\r\n"));
        Path file = log(content);

        List<String> lines = LogSearch.search(file, "", 20_000, Long.MAX_VALUE).lines();

        assertThat(lines).hasSize(20_000);
        assertThat(lines.getFirst()).isEqualTo("line-19999 " + "x".repeat(19_999 % 37));
        assertThat(lines.getLast()).isEqualTo("line-0 ");
        for (int i = 0; i < lines.size(); i++) {
            int n = 19_999 - i;
            assertThat(lines.get(i)).isEqualTo("line-" + n + " " + "x".repeat(n % 37));
        }
    }

    @Test
    void stopsAtReadLimitAndSaysSo() throws IOException {
        String content = "needle at start\n" + "hay\n".repeat(100_000);
        Path file = log(content);

        LogSearch.Result limited = LogSearch.search(file, "needle", 10, LogSearch.CHUNK);
        LogSearch.Result full = LogSearch.search(file, "needle", 10, Long.MAX_VALUE);

        assertThat(limited.lines()).isEmpty();
        assertThat(limited.exhausted()).isTrue();
        assertThat(full.lines()).containsExactly("needle at start");
        assertThat(full.exhausted()).isFalse();
    }

    @Test
    void multibyteTextIsDecoded() throws IOException {
        Path file = log("오류: 업스트림 연결 실패\n정상\n");

        assertThat(LogSearch.search(file, "업스트림", 10, Long.MAX_VALUE).lines())
                .containsExactly("오류: 업스트림 연결 실패");
    }

    @Test
    void missingFileIsReported() throws IOException {
        LogSearch.Result result = LogSearch.search(dir.resolve("none.log"), null, 10, Long.MAX_VALUE);

        assertThat(result.missing()).isTrue();
        assertThat(result.lines()).isEmpty();
    }
}
