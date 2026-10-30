package net.nginxmanager.site;

import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static net.nginxmanager.config.QConfigFile.configFile;
import static net.nginxmanager.site.QSite.site;
import static net.nginxmanager.user.QUser.user;

@Repository
public class SiteQueryRepository {

    public record SiteRow(Long id, String primaryDomain, String extraDomains, SiteType type, int listenHttpPort,
                          boolean enabled, boolean pending, Long configFileId, boolean httpsEnabled,
                          String proxyTarget, String staticRoot, String redirectTarget,
                          Long ownerId, String ownerName, Boolean ownerEnabled) {

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

    /** 멤버가 담당한 사이트의 도메인과 가져오기 원본 짝. */
    public record OwnedSite(String primaryDomain, String importedFrom) {
    }

    /** 담당자 거르기: 모두, 담당자 없음, 한 사용자. */
    public sealed interface OwnerFilter {
        OwnerFilter ALL = new All();
        OwnerFilter NONE = new Unassigned();

        record All() implements OwnerFilter {
        }

        record Unassigned() implements OwnerFilter {
        }

        record Owner(long userId) implements OwnerFilter {
        }
    }

    /** 사이트 목록. 설정 파일의 초안 여부와 담당자 이름까지 한 쿼리로 가져온다. */
    public List<SiteRow> findAllRows() {
        return findRows(OwnerFilter.ALL);
    }

    public List<SiteRow> findRows(OwnerFilter filter) {
        BooleanExpression where = switch (filter) {
            case OwnerFilter.All all -> null;
            case OwnerFilter.Unassigned none -> site.ownerId.isNull();
            case OwnerFilter.Owner owner -> site.ownerId.eq(owner.userId());
        };
        return queryFactory
                .select(Projections.constructor(SiteRow.class,
                        site.id, site.primaryDomain, site.extraDomains, site.type, site.listenHttpPort,
                        site.enabled,
                        configFile.draftContent.isNotNull().or(configFile.draftDelete.isTrue()),
                        configFile.id, site.httpsEnabled, site.proxyTarget, site.staticRoot, site.redirectTarget,
                        site.ownerId, user.username, user.enabled))
                .from(site)
                .join(site.configFile, configFile)
                .leftJoin(user).on(user.id.eq(site.ownerId))
                .where(where)
                .orderBy(site.primaryDomain.asc())
                .fetch();
    }

    public List<OwnedSite> findOwned(long ownerId) {
        return queryFactory
                .select(Projections.constructor(OwnedSite.class, site.primaryDomain, configFile.importedFrom))
                .from(site)
                .join(site.configFile, configFile)
                .where(site.ownerId.eq(ownerId))
                .fetch();
    }

    /** 사용자별 담당 사이트 수. 담당 사이트가 없는 사용자는 빠진다. */
    public Map<Long, Long> countByOwner() {
        return queryFactory
                .select(site.ownerId, site.count())
                .from(site)
                .where(site.ownerId.isNotNull())
                .groupBy(site.ownerId)
                .fetch().stream()
                .collect(Collectors.toMap(t -> t.get(site.ownerId), t -> t.get(site.count())));
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
