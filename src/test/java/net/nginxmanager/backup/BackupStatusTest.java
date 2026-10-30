package net.nginxmanager.backup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class BackupStatusTest {

    @TempDir
    Path dataDir;

    @Test
    void readsWhatNmBackupWrote() throws Exception {
        Files.writeString(dataDir.resolve("backup-status"), """
                time=2026-10-30T03:30:05+0900
                result=ok
                file=nginx-manager-20261030-033000-v1.3.0-auto.tar.gz
                bytes=1048576
                label=auto
                reason=
                """);

        BackupStatus status = new BackupStatusReader(dataDir, "03:30").read();

        assertThat(status.at()).isEqualTo(Instant.parse("2026-10-29T18:30:05Z"));
        assertThat(status.ok()).isTrue();
        assertThat(status.auto()).isTrue();
        assertThat(status.file()).isEqualTo("nginx-manager-20261030-033000-v1.3.0-auto.tar.gz");
        assertThat(status.bytes()).isEqualTo(1048576);
        assertThat(status.reason()).isNull();
        assertThat(status.schedule()).isEqualTo("03:30");
        assertThat(status.needsAttention(status.at().plus(Duration.ofHours(47)))).isFalse();
        assertThat(status.needsAttention(status.at().plus(Duration.ofHours(49)))).isTrue();
    }

    @Test
    void aFailedBackupNeedsAttentionEvenWithoutSchedule() throws Exception {
        Files.writeString(dataDir.resolve("backup-status"),
                "time=2026-10-30T03:30:05+0900\nresult=failed\nfile=\nbytes=0\nlabel=\nreason=no-space\n");

        BackupStatus status = new BackupStatusReader(dataDir, "off").read();

        assertThat(status.ok()).isFalse();
        assertThat(status.reason()).isEqualTo("no-space");
        assertThat(status.label()).isNull();
        assertThat(status.schedule()).isNull();
        assertThat(status.needsAttention(status.at())).isTrue();
    }

    @Test
    void noRecordIsOnlyAProblemWhenScheduled() throws Exception {
        assertThat(new BackupStatusReader(dataDir, "03:30").read().needsAttention(Instant.now())).isTrue();
        assertThat(new BackupStatusReader(dataDir, "off").read().needsAttention(Instant.now())).isFalse();

        Files.writeString(dataDir.resolve("backup-status"), "time=garbage\n");
        assertThat(new BackupStatusReader(dataDir, "off").read().recorded()).isFalse();
    }
}
