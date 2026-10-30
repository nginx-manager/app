package net.nginxmanager.config;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 버전에 파일 내용을 담는다(011). 이미 저장된 내용은 다시 저장하지 않고 가리키기만 한다.
 * 파일 수와 상관없이 해시 목록으로 한 번 조회한다.
 */
@Component
public class VersionContents {

    private final ConfigContentRepository repository;

    public VersionContents(ConfigContentRepository repository) {
        this.repository = repository;
    }

    /**
     * @param files 경로와 내용. 내용이 null이면 그 버전에서 지워진 파일이다. 넣은 순서대로 더한다
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void attach(ConfigVersion version, Map<String, String> files) {
        Map<String, ConfigContent> byHash = new HashMap<>();
        var hashes = files.values().stream().filter(Objects::nonNull).map(ConfigFileStore::hash).distinct().toList();
        if (!hashes.isEmpty()) {
            repository.findByHashIn(hashes).forEach(c -> byHash.put(c.getHash(), c));
        }
        files.forEach((path, content) -> {
            ConfigContent stored = content == null ? null
                    : byHash.computeIfAbsent(ConfigFileStore.hash(content), h -> repository.save(new ConfigContent(content)));
            version.addFile(path, stored);
        });
    }
}
