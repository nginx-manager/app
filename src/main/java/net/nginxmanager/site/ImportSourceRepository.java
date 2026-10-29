package net.nginxmanager.site;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ImportSourceRepository extends JpaRepository<ImportSource, Long> {

    List<ImportSource> findAllByOrderByPatternAsc();

    boolean existsByPattern(String pattern);
}
