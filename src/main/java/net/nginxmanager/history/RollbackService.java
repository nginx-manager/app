package net.nginxmanager.history;

import net.nginxmanager.apply.ApplyResult;
import net.nginxmanager.apply.ApplyService;
import net.nginxmanager.apply.FileChange;
import net.nginxmanager.common.NotFoundException;
import net.nginxmanager.config.ConfigFile;
import net.nginxmanager.config.ConfigFileRepository;
import net.nginxmanager.config.ConfigFileStore;
import net.nginxmanager.config.ConfigVersion;
import net.nginxmanager.config.ConfigVersionFile;
import net.nginxmanager.config.VersionSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 성공한 버전의 파일 내용으로 되돌린다. 초안을 거치지 않고, 적용과 같은 절차(검증 → reload → 상태 확인 → 실패 시 복구)를 밟는다.
 */
@Service
public class RollbackService {

    private final ApplyService applyService;
    private final VersionQueryRepository versionQueryRepository;
    private final ConfigFileRepository configFileRepository;
    private final ConfigFileStore store;
    private final TransactionTemplate tx;

    public RollbackService(ApplyService applyService, VersionQueryRepository versionQueryRepository,
                           ConfigFileRepository configFileRepository, ConfigFileStore store, TransactionTemplate tx) {
        this.applyService = applyService;
        this.versionQueryRepository = versionQueryRepository;
        this.configFileRepository = configFileRepository;
        this.store = store;
        this.tx = tx;
    }

    public ApplyResult rollback(long versionId, String actor) {
        return applyService.applyChanges(() -> tx.execute(status -> changesTo(versionId)),
                new ApplyService.Origin(actor, "버전 #" + versionId + "(으)로 되돌리기", VersionSource.ROLLBACK,
                        versionId));
    }

    private List<FileChange> changesTo(long versionId) {
        // 초안이 섞이면 무엇을 되돌렸는지 알 수 없으므로 먼저 적용하거나 버리게 한다
        if (configFileRepository.countPending() > 0) {
            throw new RollbackRejectedException(RollbackRejectedException.Reason.PENDING_CHANGES);
        }
        ConfigVersion target = versionQueryRepository.findWithFiles(versionId)
                .orElseThrow(() -> new NotFoundException("version " + versionId));
        if (!target.isSnapshot()) {
            throw new RollbackRejectedException(RollbackRejectedException.Reason.NOT_A_SNAPSHOT);
        }
        Map<String, String> wanted = new HashMap<>();
        for (ConfigVersionFile file : target.getFiles()) {
            wanted.put(file.getPath(), file.getContent());
        }
        List<FileChange> changes = new ArrayList<>();
        wanted.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
            if (!Objects.equals(e.getValue(), store.read(e.getKey()))) {
                changes.add(new FileChange(e.getKey(), e.getValue()));
            }
        });
        for (ConfigFile file : configFileRepository.findAllByOrderByPathAsc()) {
            if (!wanted.containsKey(file.getPath()) && store.read(file.getPath()) != null) {
                changes.add(new FileChange(file.getPath(), null));
            }
        }
        return changes;
    }
}
