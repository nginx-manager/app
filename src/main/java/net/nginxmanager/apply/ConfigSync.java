package net.nginxmanager.apply;

import net.nginxmanager.config.ConfigFile;
import net.nginxmanager.config.ConfigFileRepository;
import net.nginxmanager.config.ConfigFileStore;
import net.nginxmanager.config.ConfigVersion;
import net.nginxmanager.site.SiteReconciler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 디스크에 반영된 변경을 설정 파일 행과 사이트에 맞추고, 버전에 전체 스냅샷을 담는다. */
@Component
public class ConfigSync {

    private final ConfigFileRepository configFileRepository;
    private final ConfigFileStore store;
    private final SiteReconciler siteReconciler;

    public ConfigSync(ConfigFileRepository configFileRepository, ConfigFileStore store,
                      SiteReconciler siteReconciler) {
        this.configFileRepository = configFileRepository;
        this.store = store;
        this.siteReconciler = siteReconciler;
    }

    /**
     * @param keepDrafts true면 외부 변경 가져오기: 사용자가 쓰던 초안은 그대로 둔다.
     *                   false면 적용: 반영된 파일의 초안을 비운다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void sync(List<FileChange> changes, boolean keepDrafts) {
        for (FileChange change : changes) {
            if (change.content() == null) {
                continue;
            }
            ConfigFile file = configFileRepository.findByPath(change.path())
                    .orElseGet(() -> configFileRepository.save(new ConfigFile(change.path(), false)));
            String hash = ConfigFileStore.hash(change.content());
            if (keepDrafts) {
                file.markImported(hash);
            } else {
                file.markApplied(hash);
            }
        }
        siteReconciler.reconcile(changes.stream().map(FileChange::path).toList());
        for (FileChange change : changes) {
            if (change.content() != null) {
                continue;
            }
            configFileRepository.findByPath(change.path()).ifPresent(file -> {
                if (keepDrafts && file.getDraftContent() != null) {
                    file.markImported(null);
                } else {
                    configFileRepository.delete(file);
                }
            });
        }
    }

    /** 관리 중인 파일 전체의 지금 디스크 내용을 버전에 담는다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void snapshot(ConfigVersion version) {
        for (ConfigFile file : configFileRepository.findAllByOrderByPathAsc()) {
            String content = store.read(file.getPath());
            if (content != null) {
                version.addFile(file.getPath(), content);
            }
        }
    }
}
