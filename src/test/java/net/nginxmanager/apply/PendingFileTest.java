package net.nginxmanager.apply;

import net.nginxmanager.apply.ApplyController.PendingFile;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PendingFileTest {

    @Test
    void countsChangedLinesWithoutFileHeaders() {
        String diff = """
                --- a/nginx.conf
                +++ b/nginx.conf
                @@ -1,3 +1,3 @@
                 worker_processes 1;
                -keepalive_timeout 65;
                +keepalive_timeout 30;
                +server_tokens off;
                """;
        PendingFile file = new PendingFile(1L, "nginx.conf", false, diff);

        assertThat(file.added()).isEqualTo(2);
        assertThat(file.removed()).isEqualTo(1);
    }
}
