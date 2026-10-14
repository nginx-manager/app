package net.nginxmanager.history;

import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQueryFactory;
import net.nginxmanager.config.ConfigVersion;
import net.nginxmanager.config.VersionResult;
import net.nginxmanager.config.VersionSource;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static net.nginxmanager.config.QConfigVersion.configVersion;
import static net.nginxmanager.config.QConfigVersionFile.configVersionFile;

@Repository
public class VersionQueryRepository {

    public record VersionRow(Long id, Instant createdAt, String authorName, String message, VersionSource source,
                             VersionResult result, Long rolledBackToId, Long fileCount) {
    }

    private final JPAQueryFactory queryFactory;

    public VersionQueryRepository(JPAQueryFactory queryFactory) {
        this.queryFactory = queryFactory;
    }

    /** 최신 순 버전 목록. 작성자 이름은 버전에 함께 저장돼 있어 사용자 테이블을 읽지 않는다. */
    public List<VersionRow> findPage(int offset, int limit) {
        return queryFactory
                .select(Projections.constructor(VersionRow.class,
                        configVersion.id, configVersion.createdAt, configVersion.authorName, configVersion.message,
                        configVersion.source, configVersion.result, configVersion.rolledBackToId,
                        configVersionFile.id.count()))
                .from(configVersion)
                .leftJoin(configVersion.files, configVersionFile)
                .groupBy(configVersion.id)
                .orderBy(configVersion.id.desc())
                .offset(offset)
                .limit(limit)
                .fetch();
    }

    /** 파일 내용까지 한 번에 가져온다. */
    public Optional<ConfigVersion> findWithFiles(long id) {
        return Optional.ofNullable(queryFactory
                .selectFrom(configVersion)
                .leftJoin(configVersion.files, configVersionFile).fetchJoin()
                .where(configVersion.id.eq(id))
                .fetchOne());
    }
}
