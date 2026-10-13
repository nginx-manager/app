package net.nginxmanager.audit;

import net.nginxmanager.user.User;
import net.nginxmanager.user.UserRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class AuditLogRecorder {

    private final AuditLogRepository auditLogRepository;
    private final UserRepository userRepository;

    public AuditLogRecorder(AuditLogRepository auditLogRepository, UserRepository userRepository) {
        this.auditLogRepository = auditLogRepository;
        this.userRepository = userRepository;
    }

    /**
     * 원래 작업이 끝난 뒤(커밋·롤백 모두) 별도 트랜잭션으로 저장한다. 롤백된 시도도 남고,
     * SQLite 쓰기 잠금을 원래 트랜잭션과 다투지 않는다. 트랜잭션 밖에서 발행된 이벤트는 바로 저장한다.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMPLETION, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AuditEvent event) {
        User user = event.username() == null ? null : userRepository.findByUsername(event.username()).orElse(null);
        auditLogRepository.save(new AuditLog(user, event.username(), event.action(), event.target(),
                event.result(), event.detail()));
    }
}
