package net.nginxmanager.logs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.assertj.core.api.Assertions.assertThat;

class LogCursorTest {

    @TempDir
    Path dir;

    @Test
    void readsOnlyLinesAppendedAfterOpeningAndWaitsForLineEnd() throws IOException {
        Path file = dir.resolve("a.log");
        Files.writeString(file, "old\n");
        LogCursor cursor = new LogCursor(file);

        assertThat(cursor.readNewLines()).isEmpty();
        Files.writeString(file, "new 1\nnew 2\npart", StandardOpenOption.APPEND);
        assertThat(cursor.readNewLines()).containsExactly("new 1", "new 2");
        Files.writeString(file, "ial\n", StandardOpenOption.APPEND);
        assertThat(cursor.readNewLines()).containsExactly("partial");
    }

    @Test
    void startsOverWhenFileIsRotated() throws IOException {
        Path file = dir.resolve("a.log");
        Files.writeString(file, "a long line before rotation\n");
        LogCursor cursor = new LogCursor(file);

        Files.writeString(file, "fresh\n");

        assertThat(cursor.readNewLines()).containsExactly("fresh");
    }

    @Test
    void fileCreatedLaterIsFollowed() throws IOException {
        Path file = dir.resolve("later.log");
        LogCursor cursor = new LogCursor(file);
        assertThat(cursor.readNewLines()).isEmpty();

        Files.writeString(file, "first\n");

        assertThat(cursor.readNewLines()).containsExactly("first");
    }
}
