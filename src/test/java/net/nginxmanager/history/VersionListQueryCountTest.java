package net.nginxmanager.history;

import net.nginxmanager.config.ConfigVersion;
import net.nginxmanager.config.VersionResult;
import net.nginxmanager.config.VersionSource;
import net.nginxmanager.support.ConfigRootTest;
import net.nginxmanager.support.QueryCounter;
import net.nginxmanager.user.Role;
import net.nginxmanager.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class VersionListQueryCountTest extends ConfigRootTest {

    @Autowired
    VersionQueryRepository versionQueryRepository;
    @Autowired
    QueryCounter queryCounter;

    @Test
    void versionListQueryCountDoesNotGrowWithVersions() {
        User author = userRepository.save(new User("editor", "{noop}x", Role.EDITOR));
        queryCounter.assertConstantQueries(
                n -> {
                    for (int i = 0; i < n; i++) {
                        ConfigVersion version = new ConfigVersion(author, "v" + i, VersionSource.APPLY,
                                VersionResult.SUCCESS, null);
                        version.addFile("nginx.conf", "events {}\n");
                        version.addFile("conf.d/sites/a.conf", "server {}\n");
                        configVersionRepository.save(version);
                    }
                },
                () -> assertThat(versionQueryRepository.findPage(0, 100))
                        .allSatisfy(row -> {
                            assertThat(row.authorName()).isEqualTo("editor");
                            assertThat(row.fileCount()).isEqualTo(2L);
                        }),
                1, 50);
    }
}
