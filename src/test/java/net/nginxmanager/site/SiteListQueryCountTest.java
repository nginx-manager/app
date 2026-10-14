package net.nginxmanager.site;

import net.nginxmanager.support.ConfigRootTest;
import net.nginxmanager.support.QueryCounter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class SiteListQueryCountTest extends ConfigRootTest {

    @Autowired
    SiteService siteService;
    @Autowired
    SiteQueryRepository siteQueryRepository;
    @Autowired
    QueryCounter queryCounter;

    @Test
    void siteListQueryCountDoesNotGrowWithSites() {
        AtomicInteger seq = new AtomicInteger();
        queryCounter.assertConstantQueries(
                n -> {
                    for (int i = 0; i < n; i++) {
                        siteService.create(new SiteForm("s" + seq.incrementAndGet() + ".local", "", SiteType.PROXY,
                                80, null, "http://a:1", null), "editor");
                    }
                },
                () -> assertThat(siteQueryRepository.findAllRows()).isNotEmpty(),
                1, 50);
    }
}
