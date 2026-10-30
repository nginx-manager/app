package net.nginxmanager.config;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ConfigFileRepository extends JpaRepository<ConfigFile, Long> {

    Optional<ConfigFile> findByPath(String path);

    Optional<ConfigFile> findByImportedFrom(String importedFrom);

    List<ConfigFile> findAllByOrderByPathAsc();

    List<ConfigFile> findByPathStartingWith(String prefix);

    long countByDraftContentIsNotNullOrDraftDeleteTrue();

    boolean existsByDraftContentContaining(String text);

    List<ConfigFile> findByDraftContentIsNotNullOrDraftDeleteTrue();

    default long countPending() {
        return countByDraftContentIsNotNullOrDraftDeleteTrue();
    }
}
