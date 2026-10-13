package net.nginxmanager.audit;

import net.nginxmanager.support.IntegrationTest;
import net.nginxmanager.support.QueryCounter;
import net.nginxmanager.user.Role;
import net.nginxmanager.user.User;
import net.nginxmanager.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;


import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
class AuditLogQueryCountTest {

    @Autowired
    UserRepository userRepository;
    @Autowired
    AuditLogRepository auditLogRepository;
    @Autowired
    AuditLogQueryRepository auditLogQueryRepository;
    @Autowired
    QueryCounter queryCounter;
    @Autowired
    TransactionTemplate tx;

    @BeforeEach
    void clean() {
        auditLogRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void recentListUsesSameNumberOfQueriesRegardlessOfRows() {
        queryCounter.assertConstantQueries(this::seedLogsByDistinctUsers,
                () -> assertThat(auditLogQueryRepository.findRecent(100)).isNotEmpty(), 1, 50);
    }

    private void seedLogsByDistinctUsers(int n) {
        tx.executeWithoutResult(status -> {
            for (int i = 0; i < n; i++) {
                User user = userRepository.save(new User("user-" + System.nanoTime() + "-" + i, "x", Role.VIEWER));
                auditLogRepository.save(new AuditLog(user, user.getUsername(), AuditAction.LOGIN, null,
                        AuditResult.SUCCESS, null));
            }
        });
    }
}
