package net.nginxmanager.audit;

import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQueryFactory;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

import static net.nginxmanager.audit.QAuditLog.auditLog;
import static net.nginxmanager.user.QUser.user;

@Repository
public class AuditLogQueryRepository {

    public record AuditLogRow(Long id, Instant createdAt, String username, String userRole, AuditAction action,
                              String target, AuditResult result) {
    }

    private final JPAQueryFactory queryFactory;

    public AuditLogQueryRepository(JPAQueryFactory queryFactory) {
        this.queryFactory = queryFactory;
    }

    /** 최근 순 목록. 사용자 정보는 조인해 한 쿼리로 가져온다. */
    public List<AuditLogRow> findRecent(int limit) {
        return queryFactory
                .select(Projections.constructor(AuditLogRow.class,
                        auditLog.id, auditLog.createdAt, auditLog.username, user.role.stringValue(),
                        auditLog.action, auditLog.target, auditLog.result))
                .from(auditLog)
                .leftJoin(auditLog.user, user)
                .orderBy(auditLog.id.desc())
                .limit(limit)
                .fetch();
    }
}
