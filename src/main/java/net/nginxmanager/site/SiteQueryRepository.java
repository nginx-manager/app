package net.nginxmanager.site;

import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQueryFactory;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

import static net.nginxmanager.config.QConfigFile.configFile;
import static net.nginxmanager.site.QSite.site;

@Repository
public class SiteQueryRepository {

    public record SiteRow(Long id, String primaryDomain, String extraDomains, SiteType type, int listenHttpPort,
                          boolean enabled, boolean pending, Long configFileId, boolean httpsEnabled,
                          String proxyTarget, String staticRoot, String redirectTarget) {

        /** 목록의 대상 칸. 프록시는 백엔드 주소들, 정적은 문서 루트, 리디렉션은 보낼 주소. 원문 편집 사이트는 비운다 */
        public List<String> targets() {
            String value = switch (type) {
                case PROXY -> proxyTarget;
                case STATIC -> staticRoot;
                case REDIRECT -> redirectTarget;
                default -> null;
            };
            return value == null ? List.of() : value.lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
        }
    }

    private final JPAQueryFactory queryFactory;

    public SiteQueryRepository(JPAQueryFactory queryFactory) {
        this.queryFactory = queryFactory;
    }

    /** 사이트 목록. 설정 파일의 초안 여부까지 한 쿼리로 가져온다. */
    public List<SiteRow> findAllRows() {
        return queryFactory
                .select(Projections.constructor(SiteRow.class,
                        site.id, site.primaryDomain, site.extraDomains, site.type, site.listenHttpPort,
                        site.enabled,
                        configFile.draftContent.isNotNull().or(configFile.draftDelete.isTrue()),
                        configFile.id, site.httpsEnabled, site.proxyTarget, site.staticRoot, site.redirectTarget))
                .from(site)
                .join(site.configFile, configFile)
                .orderBy(site.primaryDomain.asc())
                .fetch();
    }

    /** 상세 화면용. 설정 파일을 함께 가져온다. */
    public Optional<Site> findWithFile(long id) {
        return Optional.ofNullable(queryFactory
                .selectFrom(site)
                .join(site.configFile, configFile).fetchJoin()
                .where(site.id.eq(id))
                .fetchOne());
    }
}
