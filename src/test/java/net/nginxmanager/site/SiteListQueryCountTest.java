package net.nginxmanager.site;

import net.nginxmanager.support.ConfigRootTest;
import net.nginxmanager.support.QueryCounter;
import net.nginxmanager.user.Role;
import net.nginxmanager.user.User;
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

    /** 담당자 이름(006)과 사용자별 담당 수도 사이트 수와 관계없이 한 쿼리다. */
    @Test
    void ownerColumnsAndCountsDoNotGrowWithSites() {
        User owner = userRepository.save(new User("owner", "{noop}x", Role.MEMBER));
        AtomicInteger seq = new AtomicInteger();
        queryCounter.assertConstantQueries(
                n -> {
                    for (int i = 0; i < n; i++) {
                        siteService.create(new SiteForm("o" + seq.incrementAndGet() + ".local", "", SiteType.PROXY,
                                80, null, "http://a:1", null), "owner", owner.getId());
                    }
                },
                () -> {
                    assertThat(siteQueryRepository.findRows(new SiteQueryRepository.OwnerFilter.Owner(owner.getId())))
                            .allSatisfy(row -> assertThat(row.ownerName()).isEqualTo("owner"));
                    assertThat(siteQueryRepository.countByOwner()).containsKey(owner.getId());
                    assertThat(siteQueryRepository.findOwned(owner.getId())).isNotEmpty();
                },
                1, 50);
    }
}
