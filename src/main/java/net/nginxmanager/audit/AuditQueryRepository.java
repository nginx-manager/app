package net.nginxmanager.audit;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQueryFactory;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static net.nginxmanager.audit.QAuditLog.auditLog;
import static net.nginxmanager.user.QUser.user;

/** 감사 로그 화면의 동적 검색. */
@Repository
public class AuditQueryRepository {

    /** 비어 있는 조건은 쓰지 않는다. 기간은 날짜 단위, to는 그날 끝까지 포함한다. */
    public record Filter(String username, AuditAction action, AuditResult result, LocalDate from, LocalDate to) {

        public static Filter none() {
            return new Filter(null, null, null, null, null);
        }
    }

    private final JPAQueryFactory queryFactory;

    public AuditQueryRepository(JPAQueryFactory queryFactory) {
        this.queryFactory = queryFactory;
    }

    public List<AuditLogQueryRepository.AuditLogRow> search(Filter filter, ZoneId zone, int offset, int limit) {
        return queryFactory
                .select(Projections.constructor(AuditLogQueryRepository.AuditLogRow.class,
                        auditLog.id, auditLog.createdAt, auditLog.username, user.role.stringValue(),
                        auditLog.action, auditLog.target, auditLog.result, auditLog.detail))
                .from(auditLog)
                .leftJoin(auditLog.user, user)
                .where(conditions(filter, zone))
                .orderBy(auditLog.id.desc())
                .offset(offset)
                .limit(limit)
                .fetch();
    }

    private static BooleanBuilder conditions(Filter filter, ZoneId zone) {
        BooleanBuilder where = new BooleanBuilder();
        if (filter.username() != null && !filter.username().isBlank()) {
            where.and(auditLog.username.eq(filter.username().trim()));
        }
        if (filter.action() != null) {
            where.and(auditLog.action.eq(filter.action()));
        }
        if (filter.result() != null) {
            where.and(auditLog.result.eq(filter.result()));
        }
        if (filter.from() != null) {
            where.and(auditLog.createdAt.goe(startOf(filter.from(), zone)));
        }
        if (filter.to() != null) {
            where.and(auditLog.createdAt.lt(startOf(filter.to().plusDays(1), zone)));
        }
        return where;
    }

    private static Instant startOf(LocalDate date, ZoneId zone) {
        return date.atStartOfDay(zone).toInstant();
    }
}
