package net.nginxmanager.audit;

import net.nginxmanager.support.ConfigRootTest;
import net.nginxmanager.support.QueryCounter;
import net.nginxmanager.user.Role;
import net.nginxmanager.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class AuditListQueryCountTest extends ConfigRootTest {

    @Autowired
    AuditQueryRepository auditQueryRepository;
    @Autowired
    QueryCounter queryCounter;

    @Test
    void auditSearchQueryCountDoesNotGrowWithRows() {
        User editor = userRepository.save(new User("editor", "{noop}x", Role.MEMBER));
        queryCounter.assertConstantQueries(
                n -> {
                    for (int i = 0; i < n; i++) {
                        auditLogRepository.save(new AuditLog(editor, "editor", AuditAction.APPLY, "version:" + i,
                                AuditResult.SUCCESS, null));
                    }
                },
                () -> assertThat(auditQueryRepository.search(AuditQueryRepository.Filter.none(), ZoneId.of("UTC"), 0, 100))
                        .allSatisfy(row -> assertThat(row.userRole()).isEqualTo("MEMBER")),
                1, 50);
    }

    @Test
    void filtersCombine() {
        User editor = userRepository.save(new User("editor", "{noop}x", Role.MEMBER));
        auditLogRepository.save(new AuditLog(editor, "editor", AuditAction.APPLY, "version:1", AuditResult.SUCCESS, null));
        auditLogRepository.save(new AuditLog(editor, "editor", AuditAction.APPLY, "version:2", AuditResult.FAILURE, "x"));
        auditLogRepository.save(new AuditLog(null, "ghost", AuditAction.LOGIN, null, AuditResult.FAILURE, null));
        LocalDate today = LocalDate.now(ZoneId.of("UTC"));

        assertThat(auditQueryRepository.search(new AuditQueryRepository.Filter("editor", AuditAction.APPLY,
                AuditResult.FAILURE, today, today), ZoneId.of("UTC"), 0, 10))
                .extracting(AuditLogQueryRepository.AuditLogRow::target).containsExactly("version:2");
        assertThat(auditQueryRepository.search(new AuditQueryRepository.Filter(null, null, AuditResult.FAILURE,
                null, null), ZoneId.of("UTC"), 0, 10)).hasSize(2);
        assertThat(auditQueryRepository.search(new AuditQueryRepository.Filter(null, null, null,
                today.plusDays(1), null), ZoneId.of("UTC"), 0, 10)).isEmpty();
    }
}
