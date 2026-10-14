package net.nginxmanager.config;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ConfigFileRepository extends JpaRepository<ConfigFile, Long> {

    Optional<ConfigFile> findByPath(String path);

    List<ConfigFile> findAllByOrderByPathAsc();

    long countByDraftContentIsNotNullOrDraftDeleteTrue();

    default long countPending() {
        return countByDraftContentIsNotNullOrDraftDeleteTrue();
    }
}
