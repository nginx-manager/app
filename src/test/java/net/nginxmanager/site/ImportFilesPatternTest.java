package net.nginxmanager.site;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** 권한이 없어 못 연 디렉터리 가운데 패턴과 관계있는 것만 알린다. */
class ImportFilesPatternTest {

    @Test
    void onlyDirectoriesThePatternCanReachAreReported() {
        assertThat(ImportFiles.couldMatch("/home/*/nginx/*.conf", Path.of("/home/alice"))).isTrue();
        assertThat(ImportFiles.couldMatch("/home/*/nginx/*.conf", Path.of("/home/alice/nginx"))).isTrue();
        assertThat(ImportFiles.couldMatch("/home/*/nginx/*.conf", Path.of("/home/alice/www"))).isFalse();
        assertThat(ImportFiles.couldMatch("/home/*/nginx/*.conf", Path.of("/home/alice/nginx/old"))).isFalse();
        assertThat(ImportFiles.couldMatch("/home/**/*.conf", Path.of("/home/alice/deep/er"))).isTrue();
    }
}
