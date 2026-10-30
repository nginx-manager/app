package net.nginxmanager.history;

import com.querydsl.core.Tuple;
import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQueryFactory;
import net.nginxmanager.config.ConfigVersion;
import net.nginxmanager.config.VersionResult;
import net.nginxmanager.config.VersionSource;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    /** 고른 버전들의 목록 줄(최신 순). {@link #findPage}와 같은 프로젝션이다. */
    public List<VersionRow> findRows(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return queryFactory
                .select(Projections.constructor(VersionRow.class,
                        configVersion.id, configVersion.createdAt, configVersion.authorName, configVersion.message,
                        configVersion.source, configVersion.result, configVersion.rolledBackToId,
                        configVersionFile.id.count()))
                .from(configVersion)
                .leftJoin(configVersion.files, configVersionFile)
                .where(configVersion.id.in(ids))
                .groupBy(configVersion.id)
                .orderBy(configVersion.id.desc())
                .fetch();
    }

    /**
     * 이 경로들의 내용이 바뀐 버전(최신 순, 014). 성공한 버전은 바로 앞 성공 버전과 내용(012의 내용 id)이 다르거나
     * 파일이 생기거나 사라졌을 때, 실패한 버전은 그 경로를 적용하려 했을 때 넣는다. 내용은 읽지 않는다.
     */
    public List<Long> versionsTouching(Collection<String> paths) {
        Map<Long, Map<String, Long>> filesByVersion = new HashMap<>();
        for (Tuple t : queryFactory
                .select(configVersionFile.configVersion.id, configVersionFile.path, configVersionFile.content.id)
                .from(configVersionFile)
                .where(configVersionFile.path.in(paths))
                .fetch()) {
            // 지워진 파일(내용 없음)은 0으로 둔다(실패한 삭제 시도)
            filesByVersion.computeIfAbsent(t.get(0, Long.class), k -> new HashMap<>())
                    .put(t.get(1, String.class), Objects.requireNonNullElse(t.get(2, Long.class), 0L));
        }
        List<Long> touched = new ArrayList<>();
        Map<String, Long> last = Map.of();
        for (Tuple t : queryFactory.select(configVersion.id, configVersion.result).from(configVersion)
                .orderBy(configVersion.id.asc()).fetch()) {
            Long id = t.get(configVersion.id);
            Map<String, Long> files = filesByVersion.getOrDefault(id, Map.of());
            if (t.get(configVersion.result) == VersionResult.SUCCESS) {
                if (!files.equals(last)) {
                    touched.add(id);
                }
                last = files;
            } else if (!files.isEmpty()) {
                touched.add(id);
            }
        }
        Collections.reverse(touched);
        return touched;
    }

    /** 파일 내용까지 한 번에 가져온다. */
    public Optional<ConfigVersion> findWithFiles(long id) {
        return Optional.ofNullable(queryFactory
                .selectFrom(configVersion)
                .leftJoin(configVersion.files, configVersionFile).fetchJoin()
                .leftJoin(configVersionFile.content).fetchJoin()
                .where(configVersion.id.eq(id))
                .fetchOne());
    }
}
